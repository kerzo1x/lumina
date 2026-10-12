#version 460

layout (binding = 0) uniform sampler2D Sampler0;

layout (location = 0) in vec2 texCoord;

layout (location = 0) out vec4 fragColor;

void main() {
    // The projection has its near plane at 0.05 and no far plane, which makes depth = 1 - near / distance.
    // Open sky is stored as a distance no terrain can reach.
    float depth = textureLod(Sampler0, texCoord, 0.0).r;
    fragColor = vec4(min(0.05 / max(1.0 - depth, 1.0e-7), 60000.0), 0.0, 0.0, 1.0);
}
