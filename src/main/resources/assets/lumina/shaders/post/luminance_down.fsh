#version 460

// First step of the exposure metering: every pixel of a 16x9 grid averages the log luminance of its share of the
// frame. 36 bilinear taps spread over the cell, each already averaging four pixels, are plenty for an average.

layout (binding = 0) uniform sampler2D Sampler0;

layout (location = 0) in vec2 texCoord;

layout (location = 0) out vec4 fragColor;

void main() {
    vec2 cell = 1.0 / vec2(16.0, 9.0);
    vec2 origin = floor(texCoord / cell) * cell;
    float sum = 0.0;

    for (int y = 0; y < 6; ++y) {
        for (int x = 0; x < 6; ++x) {
            vec3 c = textureLod(Sampler0, origin + (vec2(x, y) + 0.5) / 6.0 * cell, 0.0).rgb;
            sum += log(dot(c, vec3(0.2126, 0.7152, 0.0722)) + 0.004);
        }
    }

    fragColor = vec4(sum / 36.0, 0.0, 0.0, 1.0);
}
