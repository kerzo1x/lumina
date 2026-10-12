#version 460

layout (binding = 0) uniform UniformBufferObject {
    mat4 LuminaShadowMat;
};

layout (location = 0) in vec3 Position;

void main() {
    vec4 clip = LuminaShadowMat * vec4(Position, 1.0);
    clip.z = clamp(clip.z, 0.0, 1.0);
    gl_Position = clip;
}
