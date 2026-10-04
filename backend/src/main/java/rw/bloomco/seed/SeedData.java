package rw.bloomco.seed;

import java.util.List;

/** Demo catalogue, accounts and texts used by {@link DataSeeder}. */
final class SeedData {

    private SeedData() {}

    static String img(String id) {
        return "https://images.unsplash.com/photo-" + id + "?w=800&q=80&auto=format&fit=crop";
    }

    record Category(String name, String description) {}

    record Product(String name, String category, long price, int discount, int stock, String imageId, List<String> occasions,
            int sold, String description) {}

    record Gift(String code, String name, String icon, long price) {}

    record Promo(String code, String title, String description, int percent, long minOrder) {}

    record Staff(String first, String last, String email, String phone, String staffRole) {}

    record Customer(String first, String last, String email, String phone, String province, String district, String sector, String street) {}

    static final List<Category> CATEGORIES = List.of(
            new Category("Roses", "Timeless roses in every colour"),
            new Category("Bouquets", "Hand-tied mixed bouquets by our florists"),
            new Category("Wedding Flowers", "Bridal bouquets and wedding arrangements"),
            new Category("Birthday Flowers", "Bright blooms to celebrate another year"),
            new Category("Romantic Flowers", "Say it with love"),
            new Category("Gift Flowers", "Thoughtful flowers for any gesture"),
            new Category("Seasonal Flowers", "Fresh picks of the season"));

    static final List<Product> PRODUCTS = List.of(
            new Product("Red Roses", "Roses", 15000, 0, 40, "1494972308805-463bc619d34e", List.of("valentine", "romantic", "anniversary"), 182,
                    "A dozen velvety long-stem red roses - the classic declaration of love, wrapped in kraft paper and satin ribbon."),
            new Product("Pink Tulips", "Seasonal Flowers", 12500, 0, 35, "1561181286-d3fee7d55364", List.of("birthday", "mothers-day", "thank-you"), 121,
                    "Ten fresh pink tulips that open gracefully over the week. Light, cheerful and perfect for any spring moment."),
            new Product("Lilies", "Gift Flowers", 18000, 0, 20, "1525310072745-f49212b5ac6d", List.of("congratulations", "sympathy", "thank-you"), 74,
                    "Fragrant pink oriental lilies that fill the room with a sweet scent. Elegant and long-lasting."),
            new Product("Sunflowers", "Seasonal Flowers", 10000, 0, 50, "1455659817273-f96807779a8a", List.of("get-well", "birthday", "congratulations"), 158,
                    "A sunny bunch of sunflowers to brighten anyone's day. Guaranteed to bring a smile."),
            new Product("Wildflowers", "Seasonal Flowers", 8500, 0, 30, "1490750967868-88aa4486c946", List.of("thank-you", "get-well"), 66,
                    "A free-spirited mix of seasonal wildflowers, picked fresh and loosely arranged for a natural, meadow look."),
            new Product("Peonies", "Romantic Flowers", 22000, 0, 15, "1582794543139-8ac9cb0f7b11", List.of("wedding", "anniversary", "romantic"), 97,
                    "Lush, ruffled pink peonies - the ultimate luxury flower. Soft, romantic and beautifully fragrant."),
            new Product("Orchids", "Gift Flowers", 25000, 10, 12, "1610397648930-477b8c7f0943", List.of("congratulations", "gift", "thank-you"), 41,
                    "An exotic pink phalaenopsis orchid stem. Elegant, modern and blooms for weeks with very little care."),
            new Product("Daisies", "Seasonal Flowers", 7500, 0, 45, "1508784411316-02b8cd4d3a3a", List.of("get-well", "birthday"), 88,
                    "Simple, pure and joyful white daisies with golden hearts - a breath of fresh air."),
            new Product("Carnations", "Gift Flowers", 9000, 0, 40, "1548094990-c16ca90f1f0d", List.of("mothers-day", "thank-you"), 59,
                    "Ruffled red carnations symbolising admiration and deep love. Long-lasting and great value."),
            new Product("Hydrangeas", "Wedding Flowers", 16000, 0, 18, "1471696035578-3d8c78d99684", List.of("wedding", "thank-you"), 47,
                    "Big, cloud-like blue hydrangea heads - a statement on any table and a wedding favourite."),
            new Product("Lavender", "Gift Flowers", 11000, 0, 25, "1565011523534-747a8601f10a", List.of("get-well", "thank-you", "gift"), 52,
                    "Calming, fragrant lavender in a ceramic pot. Brings serenity to any room and dries beautifully."),
            new Product("Mixed Bouquet", "Bouquets", 20000, 0, 25, "1487530811176-3780de880c2d", List.of("birthday", "congratulations", "celebration"), 143,
                    "Our florist's signature mix of roses, astrantia, berries and eucalyptus - every bouquet is unique."),
            new Product("Rainbow Roses", "Roses", 28000, 0, 10, "1508610048659-a06b669e3321", List.of("birthday", "celebration", "graduation"), 38,
                    "A magical bunch of rainbow-dyed roses. Bold, playful and unforgettable."),
            new Product("Blush Pink Roses", "Roses", 17000, 15, 30, "1591886960571-74d43a9d4166", List.of("romantic", "mothers-day", "anniversary"), 105,
                    "Soft garden roses in blush and cream tones, arranged for a gentle, romantic feel."),
            new Product("Bridal Bouquet", "Wedding Flowers", 45000, 0, 8, "1525258946800-98cfd641d0de", List.of("wedding", "proposal"), 23,
                    "A hand-tied bridal bouquet of peach roses, white spray roses and baby's breath. Made to order."),
            new Product("Birthday Bloom Bouquet", "Birthday Flowers", 19500, 0, 22, "1563170351-be82bc888aa4", List.of("birthday", "celebration"), 112,
                    "Peach roses, pink carnations and wild greens - a happy, colourful way to say Happy Birthday!"),
            new Product("Heart of Roses", "Romantic Flowers", 35000, 20, 10, "1526047932273-341f2a7631f9", List.of("valentine", "proposal", "romantic"), 64,
                    "Pink and yellow roses arranged in a heart - the most romantic surprise you can send."),
            new Product("Peach Garden Bouquet", "Bouquets", 23000, 0, 16, "1595981267035-7b04ca84a82d", List.of("mothers-day", "birthday", "thank-you"), 71,
                    "A garden-style bouquet of peach roses, carnations and wax flowers in a glass vase."),
            new Product("White Tulips", "Seasonal Flowers", 13500, 0, 28, "1589994160839-163cd867cfe8", List.of("wedding", "sympathy", "thank-you"), 44,
                    "Crisp, pure white tulips - minimal, modern and endlessly elegant."),
            new Product("Dahlia Dream", "Seasonal Flowers", 14500, 0, 14, "1546842931-886c185b4c8c", List.of("birthday", "congratulations"), 29,
                    "Peach and coral dahlias with layered petals - a showstopper of the season."),
            new Product("Get Well Sunshine Wrap", "Gift Flowers", 15500, 0, 20, "1567696153798-9111f9cd3d0d", List.of("get-well", "gift"), 57,
                    "Sunflowers and white blooms wrapped in kraft paper with a get-well card. Pure sunshine."),
            new Product("Celebration Bouquet", "Birthday Flowers", 21000, 0, 18, "1533616688419-b7a585564566", List.of("congratulations", "graduation", "celebration"), 63,
                    "Coral roses, snapdragons and thistle in a lively, celebratory arrangement."),
            new Product("Single Red Rose", "Romantic Flowers", 3500, 0, 80, "1559563362-c667ba5f5480", List.of("valentine", "romantic"), 210,
                    "One perfect red rose, gift-wrapped. Small gesture, big meaning."),
            new Product("Wedding Blush Arrangement", "Wedding Flowers", 38000, 0, 6, "1563241527-3004b7be0ffd", List.of("wedding", "anniversary"), 19,
                    "Blush peonies, garden roses and eucalyptus in a rustic arrangement for tables and ceremonies."),
            new Product("Flame Tulips", "Seasonal Flowers", 14000, 0, 20, "1468327768560-75b778cbb551", List.of("birthday", "thank-you"), 31,
                    "Striking red-and-white flame tulips - a cheerful, unusual twist on a classic."),
            new Product("Anthurium", "Gift Flowers", 19000, 0, 12, "1567748157439-651aca2ff064", List.of("gift", "congratulations"), 15,
                    "Glossy red anthuriums - tropical, bold and long-lasting. A gift that lasts for weeks."));

    static final List<Gift> GIFT_OPTIONS = List.of(
            new Gift("gift_wrap", "Gift wrapping", "🎁", 2000),
            new Gift("greeting_card", "Greeting card", "💌", 1500),
            new Gift("chocolate", "Chocolate box", "🍫", 8000),
            new Gift("teddy_bear", "Teddy bear", "🧸", 12000),
            new Gift("balloons", "Balloons", "🎈", 5000),
            new Gift("premium_packaging", "Premium packaging", "💐", 6000));

    static final List<Promo> PROMOTIONS = List.of(
            new Promo("VALENTINE20", "20% OFF Valentine's Collection ❤️", "Celebrate love with 20% off every order over RWF 20,000.", 20, 20000),
            new Promo("BLOOM10", "Welcome gift: 10% off", "10% off your first bouquet - no minimum.", 10, 0),
            new Promo("MOTHERSDAY15", "15% off Mother's Day flowers 🌷", "Spoil mum with 15% off orders over RWF 30,000.", 15, 30000));

    static final List<Staff> STAFF = List.of(
            new Staff("Aline", "Uwase", "aline.staff@bloomandco.rw", "0788000101", "order_manager"),
            new Staff("Eric", "Habimana", "eric.delivery@bloomandco.rw", "0788000102", "delivery_staff"),
            new Staff("Grace", "Mukamana", "grace.support@bloomandco.rw", "0788000103", "customer_support"),
            new Staff("Jean", "Niyonzima", "jean.inventory@bloomandco.rw", "0788000104", "inventory_staff"));

    static final List<Customer> CUSTOMERS = List.of(
            new Customer("Melissa", "Ineza", "melissa@example.com", "0788111222", "Kigali City", "Gasabo", "Kimihurura", "KG 9 Ave, House 12"),
            new Customer("Kevin", "Mugisha", "kevin@example.com", "0788333444", "Kigali City", "Kicukiro", "Niboye", "KK 15 Rd, House 4"),
            new Customer("Diane", "Uwimana", "diane@example.com", "0722555666", "Kigali City", "Nyarugenge", "Nyamirambo", "KN 2 St, House 31"),
            new Customer("Patrick", "Nkurunziza", "patrick@example.com", "0738777888", "Southern Province", "Huye", "Ngoma", "Main Rd, near UR campus"),
            new Customer("Sandrine", "Ishimwe", "sandrine@example.com", "0789999000", "Kigali City", "Gasabo", "Remera", "KG 11 Ave, Apt 3B"));

    static final List<String[]> REVIEW_TEXTS = List.of(
            new String[] {"5", "Absolutely stunning! Delivered on time and the flowers lasted more than a week."},
            new String[] {"5", "My girlfriend loved them. The handwritten message was a lovely touch ❤️"},
            new String[] {"4", "Beautiful arrangement, delivery was a little later than the slot but still great."},
            new String[] {"5", "Fresh, fragrant and exactly like the photo. Will order again!"},
            new String[] {"4", "Very pretty and good value. Packaging was premium."},
            new String[] {"5", "Best florist in Kigali. My mum cried happy tears 🌷"});

    static final List<String[]> MESSAGES = List.of(
            new String[] {"birthday", "Happy Birthday! May your day be as beautiful as these flowers. ❤️"},
            new String[] {"romantic", "Just because I love you. 🌹"},
            new String[] {"congratulations", "Congratulations on this amazing achievement!"},
            new String[] {"anniversary", "Happy anniversary, my love. Here is to many more years."},
            new String[] {"thank_you", "Thank you for everything you do."});

    static final List<String> SLOTS = List.of("08:00 - 10:00", "10:00 - 12:00", "12:00 - 14:00", "14:00 - 16:00", "16:00 - 18:00", "18:00 - 20:00");
    static final List<String> METHODS = List.of("mtn_momo", "mtn_momo", "airtel_money", "card", "cash_on_delivery");
    static final List<String> RECIPIENTS = List.of("Sarah", "Mama", "Alice", "Chris", "Nadine");
}
