#version 300 es

uniform vec2 maxDispersionHandle;
uniform float accHandle;
uniform float pointSizeHandle;
uniform float particleSize;
uniform float deltaTimeHandle;
uniform float minDuration;
uniform float time;
uniform float maxDuration;
uniform float easeDuration;
out float oSeed;
out float oDuration;
out float oX;
out float alpha;
out vec2 oPos;
out vec2 oVelocity;
out vec2 oTexCord;
out vec2 texCord;
layout(location = 0) in vec2 fPos;
layout(location = 1) in vec2 fVelocity;
layout(location = 2) in vec2 fTexCord;
layout(location = 3) in float fDuration;
layout(location = 4) in float fX;
layout(location =5) in float fSeed;
void main() {
    float phaseEase = min(0.95, 1.1*(max(0.0, min(easeDuration, time)) / easeDuration) / fX);
    float phase = phaseEase * phaseEase * phaseEase * phaseEase * phaseEase;
    if (fDuration < 0.0) {
        oTexCord = vec2(fPos.x / 2.0 + 0.5, -fPos.y / 2.0 + 0.5);
        float direction = fract(fSeed * 1200.120) * (3.14 * 2.0);
        float velocityValue = (0.1 + fract(fSeed *558.558) * (0.2 - 0.1));
        vec2 velocity = vec2(velocityValue * maxDispersionHandle.x, velocityValue * maxDispersionHandle.y);
        oVelocity = vec2(cos(direction) * velocity.x, sin(direction) * velocity.y);
        oDuration = minDuration + fract(fSeed *4923.44) * (maxDuration - minDuration);
    } else {
        oTexCord = fTexCord;
        oVelocity = fVelocity + vec2(0.0, deltaTimeHandle * accHandle * phase);
        oDuration = max(0.0, fDuration - deltaTimeHandle * phase);
    }
    oPos = fPos + fVelocity * deltaTimeHandle * phase;
    oX = fX;
    oSeed = fSeed;
    texCord = oTexCord;
    float diff = pointSizeHandle - particleSize;
    gl_PointSize = pointSizeHandle - (diff * phase);
    gl_Position = vec4(fPos, 0.0, 1.0);
    alpha = max(0.0, min(0.3, oDuration) / 0.3);
}