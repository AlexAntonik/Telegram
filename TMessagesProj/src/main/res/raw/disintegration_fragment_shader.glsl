#version 300 es

precision mediump float;
uniform sampler2D textureHandle;
uniform vec2 sizeHandle;
out vec4 vColor;
in float alpha;
in vec2 texCord;
void main() {
    vec4 color = texture(textureHandle, texCord + (sizeHandle * (gl_PointCoord - 0.5)));
    vColor = vec4(color.rgb, color.a * alpha);
}