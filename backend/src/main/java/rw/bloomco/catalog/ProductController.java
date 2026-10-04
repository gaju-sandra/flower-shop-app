package rw.bloomco.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import rw.bloomco.common.ApiException;
import rw.bloomco.common.Json;
import rw.bloomco.common.Paging;
import rw.bloomco.common.Text;
import rw.bloomco.common.UploadService;
import rw.bloomco.security.AuthUser;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final Set<String> SORTS = Set.of("newest", "popular", "rating", "price_asc", "price_desc", "name");

    /** Product fields for create / update (JSON or multipart form). Null = "not provided". */
    public static class ProductBody {
        @Size(min = 2, max = 120, message = "Name must be 2-120 characters") private String name;
        @Size(max = 2000) private String description;
        private String categoryId;
        private List<@Size(max = 40) String> occasions;
        @DecimalMin(value = "0", message = "Price cannot be negative") @DecimalMax("10000000") private Double price;
        @Min(0) @Max(value = 90, message = "Discount must be 0-90%") private Integer discountPercent;
        @Min(value = 0, message = "Stock cannot be negative") @Max(100_000) private Integer stock;
        @Size(max = 500) private String imageUrl;
        @Pattern(regexp = "active|inactive", message = "Status must be active or inactive") private String status;

        public String name() { return name; }
        public String description() { return description; }
        public List<String> occasions() { return occasions; }
        public Double price() { return price; }
        public Integer discountPercent() { return discountPercent; }
        public Integer stock() { return stock; }
        public String imageUrl() { return imageUrl; }
        public String status() { return status; }

        public void setName(String v) { name = v; }
        public void setDescription(String v) { description = v; }
        public void setCategoryId(String v) { categoryId = v; }
        public void setOccasions(List<String> v) { occasions = v; }
        public void setPrice(Double v) { price = v; }
        public void setDiscountPercent(Integer v) { discountPercent = v; }
        public void setStock(Integer v) { stock = v; }
        public void setImageUrl(String v) { imageUrl = v; }
        public void setStatus(String v) { status = v; }

        /** True when the request mentioned categoryId at all ("" clears it). */
        public boolean categoryIdSet() {
            return categoryId != null;
        }

        public Integer categoryId() {
            if (categoryId == null || categoryId.isBlank() || "null".equals(categoryId)) return null;
            try {
                return Integer.valueOf(categoryId.trim());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("Invalid category");
            }
        }

        public List<String> occasionList() {
            if (occasions == null) return List.of();
            return occasions.stream().flatMap(o -> java.util.Arrays.stream(o.split(","))).map(String::trim)
                    .filter(x -> !x.isEmpty()).limit(12).toList();
        }
    }

    public record CategoryBody(
            @NotBlank(message = "Category name is required") @Size(min = 2, max = 60) String name,
            @Size(max = 300) String description) {}

    public record StockBody(@NotNull @Min(0) @Max(100_000) Integer stock) {}

    private final ProductService products;
    private final ReviewService reviews;
    private final UploadService uploads;

    public ProductController(ProductService products, ReviewService reviews, UploadService uploads) {
        this.products = products;
        this.reviews = reviews;
        this.uploads = uploads;
    }

    // ------------------------------------------------------------------ public catalogue

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String search, @RequestParam(required = false) String category,
            @RequestParam(required = false) String occasion, @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice, @RequestParam(required = false) String sort,
            @RequestParam(required = false) String onSale, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit) {
        var filter = new ProductService.Filter(trim(search, 80), category, occasion, minPrice, maxPrice,
                Text.oneOf(sort, SORTS, "sort"), truthy(onSale), false, false);
        var body = products.list(filter, Paging.of(page, limit, 12, 48));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePublic()).body(body);
    }

    @GetMapping("/categories")
    public List<Map<String, Object>> categories() {
        return products.categories();
    }

    @GetMapping("/{idOrSlug}")
    public Map<String, Object> detail(@PathVariable String idOrSlug) {
        var product = products.get(idOrSlug, false);
        return Json.obj("product", product, "related", products.related(product),
                "reviews", reviews.forProduct((Integer) product.get("id")));
    }

    // ------------------------------------------------------------------ back office

    @GetMapping("/admin/all")
    @PreAuthorize("hasAuthority('products:read-admin')")
    public Map<String, Object> adminList(
            @RequestParam(required = false) String search, @RequestParam(required = false) String category,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String lowStock,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer limit) {
        var filter = new ProductService.Filter(trim(search, 80), category, null, null, null,
                sort == null ? "newest" : Text.oneOf(sort, SORTS, "sort"), false, truthy(lowStock), true);
        return products.list(filter, Paging.of(page, limit, 20, 100));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('products:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createJson(@Valid @RequestBody ProductBody body, @AuthenticationPrincipal AuthUser user) {
        return products.create(body, Text.blankToNull(body.imageUrl()), user.id());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('products:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createForm(@Valid @ModelAttribute ProductBody body,
            @RequestPart(name = "image", required = false) MultipartFile image, @AuthenticationPrincipal AuthUser user) {
        String url = uploads.saveImage(image);
        return products.create(body, url != null ? url : Text.blankToNull(body.imageUrl()), user.id());
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('products:manage')")
    public Map<String, Object> updateJson(@PathVariable int id, @Valid @RequestBody ProductBody body,
            @AuthenticationPrincipal AuthUser user) {
        return products.update(id, body, body.imageUrl(), user.id());
    }

    @PutMapping(path = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('products:manage')")
    public Map<String, Object> updateForm(@PathVariable int id, @Valid @ModelAttribute ProductBody body,
            @RequestPart(name = "image", required = false) MultipartFile image, @AuthenticationPrincipal AuthUser user) {
        String url = uploads.saveImage(image);
        return products.update(id, body, url != null ? url : body.imageUrl(), user.id());
    }

    /** Staff (inventory) may only change stock levels. */
    @PatchMapping("/{id}/stock")
    @PreAuthorize("hasAuthority('products:update-stock')")
    public Map<String, Object> stock(@PathVariable int id, @Valid @RequestBody StockBody body, @AuthenticationPrincipal AuthUser user) {
        return products.updateStock(id, body.stock(), user.id());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('products:manage')")
    public ResponseEntity<Void> delete(@PathVariable int id, @AuthenticationPrincipal AuthUser user) {
        products.delete(id, user.id());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ categories

    @PostMapping("/categories")
    @PreAuthorize("hasAuthority('categories:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createCategory(@Valid @RequestBody CategoryBody body) {
        return products.createCategory(body.name(), body.description());
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('categories:manage')")
    public Map<String, Object> updateCategory(@PathVariable int id, @Valid @RequestBody CategoryBody body) {
        return products.updateCategory(id, body.name(), body.description());
    }

    @DeleteMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('categories:manage')")
    public ResponseEntity<Void> deleteCategory(@PathVariable int id) {
        products.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }

    static boolean truthy(String v) {
        return "true".equals(v) || "1".equals(v);
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
