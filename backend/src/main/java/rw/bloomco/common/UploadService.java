package rw.bloomco.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import rw.bloomco.config.AppProperties;

/** Image uploads: whitelisted types (checked by magic bytes too), random file names, 3 MB cap. */
@Service
public class UploadService {

    private static final Map<String, String> ALLOWED = Map.of(
            "image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp", "image/gif", ".gif");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path dir;

    public UploadService(AppProperties props) throws IOException {
        this.dir = Path.of(props.uploadDir()).toAbsolutePath().normalize();
        Files.createDirectories(dir);
    }

    /** Saves the image and returns its public URL (/uploads/...), or null when no file was sent. */
    public String saveImage(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        String ext = ALLOWED.get(file.getContentType());
        if (ext == null) throw ApiException.badRequest("Only JPG, PNG, WEBP or GIF images are allowed");
        if (file.getSize() > 3 * 1024 * 1024) throw ApiException.badRequest("Images must be 3 MB or smaller");
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(12);
            if (!looksLikeImage(head)) throw ApiException.badRequest("That file is not a valid image");
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read the uploaded file");
        }
        byte[] rnd = new byte[6];
        RANDOM.nextBytes(rnd);
        String name = System.currentTimeMillis() + "-" + HexFormat.of().formatHex(rnd) + ext;
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Could not store upload", e);
        }
        return "/uploads/" + name;
    }

    private static boolean looksLikeImage(byte[] b) {
        if (b.length < 4) return false;
        boolean jpeg = (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
        boolean png = (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        boolean gif = b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
        boolean webp = b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E';
        return jpeg || png || gif || webp;
    }
}
