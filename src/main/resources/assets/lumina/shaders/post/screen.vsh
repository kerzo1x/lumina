#version 460

layout (location = 0) out vec2 texCoord;

void main() {
    // Single triangle covering the screen. The viewport is flipped, so clip +Y is the top row (v = 0)
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2) * 2.0 - 1.0;
    texCoord = vec2(pos.x * 0.5 + 0.5, 0.5 - pos.y * 0.5);
    gl_Position = vec4(pos, 0.0, 1.0);
}
