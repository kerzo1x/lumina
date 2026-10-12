package dev.lumina.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads GLSL from the mod jar and inlines {@code #include "file"} from the include folder,
 * so compilation does not depend on VulkanMod's include path lookup.
 */
public final class ShaderSource {
    private static final String ROOT = "/assets/lumina/shaders/";
    private static final Pattern INCLUDE = Pattern.compile("^[ \\t]*#include[ \\t]+\"([^\"]+)\"[ \\t]*$", Pattern.MULTILINE);

    private ShaderSource() {
    }

    public static String load(String path) {
        return resolveIncludes(read(path), path, 0);
    }

    private static String resolveIncludes(String source, String origin, int depth) {
        if (depth > 8)
            throw new IllegalStateException("Include depth exceeded in " + origin);

        Matcher matcher = INCLUDE.matcher(source);
        StringBuilder out = new StringBuilder();

        while (matcher.find()) {
            String included = "include/" + matcher.group(1);
            String replacement = resolveIncludes(read(included), included, depth + 1);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(out);
        return out.toString();
    }

    private static String read(String path) {
        try (InputStream stream = ShaderSource.class.getResourceAsStream(ROOT + path)) {
            if (stream == null)
                throw new IllegalStateException("Shader source not found: " + ROOT + path);

            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read shader source " + path, e);
        }
    }
}
