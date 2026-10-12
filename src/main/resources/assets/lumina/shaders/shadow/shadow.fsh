#version 460

layout (binding = 3) uniform sampler2D Sampler0;

layout (location = 0) in vec2 texCoord0;

layout (location = 0) out vec4 fragColor;

void main() {
    if (textureLod(Sampler0, texCoord0, 0.0).a < 0.1) {
        discard;
    }

    fragColor = vec4(1.0);
}
