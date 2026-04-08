// from https://learnopengl.com/PBR/IBL/Specular-IBL
#version 330 core
out vec4 FragColor;
in vec3 localPos;

uniform samplerCube environmentMap;
uniform float roughness;
uniform int u_rgbm;

const float PI = 3.14159265359;

// float RadicalInverse_VdC(uint bits);
float RadicalInverse_VdC(uint bits)
{
    bits = (bits << 16u) | (bits >> 16u);
    bits = ((bits & 0x55555555u) << 1u) | ((bits & 0xAAAAAAAAu) >> 1u);
    bits = ((bits & 0x33333333u) << 2u) | ((bits & 0xCCCCCCCCu) >> 2u);
    bits = ((bits & 0x0F0F0F0Fu) << 4u) | ((bits & 0xF0F0F0F0u) >> 4u);
    bits = ((bits & 0x00FF00FFu) << 8u) | ((bits & 0xFF00FF00u) >> 8u);
    return float(bits) * 2.3283064365386963e-10; // / 0x100000000
}

// vec2 Hammersley(uint i, uint N);
vec2 Hammersley(uint i, uint N)
{
    return vec2(float(i)/float(N), RadicalInverse_VdC(i));
}

// vec3 ImportanceSampleGGX(vec2 Xi, vec3 N, float roughness);
vec3 ImportanceSampleGGX(vec2 Xi, vec3 N, float roughness)
{
    float a = roughness*roughness;

    float phi = 2.0 * PI * Xi.x;
    float cosTheta = sqrt((1.0 - Xi.y) / (1.0 + (a*a - 1.0) * Xi.y));
    float sinTheta = sqrt(1.0 - cosTheta*cosTheta);

    // from spherical coordinates to cartesian coordinates
    vec3 H;
    H.x = cos(phi) * sinTheta;
    H.y = sin(phi) * sinTheta;
    H.z = cosTheta;

    // from tangent-space vector to world-space sample vector
    vec3 up        = abs(N.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 tangent   = normalize(cross(up, N));
    vec3 bitangent = cross(N, tangent);

    vec3 sampleVec = tangent * H.x + bitangent * H.y + N * H.z;
    return normalize(sampleVec);
}

vec3 sampleEnvironment(vec3 dir) {
    vec4 color = texture(environmentMap, dir);
    if (u_rgbm == 1) {
        return color.rgb * color.a * 255.0;
    }
    return color.rgb;
}

uniform int u_sampleCount;

void main()
{
    vec3 N = normalize(localPos);
    vec3 R = N;
    vec3 V = R;

    uint SAMPLE_COUNT = uint(max(1, u_sampleCount));
    float totalWeight = 0.0;
    vec3 prefilteredColor = vec3(0.0);
    for(uint i = 0u; i < SAMPLE_COUNT; ++i)
    {
        vec2 Xi = Hammersley(i, SAMPLE_COUNT);
        vec3 H  = ImportanceSampleGGX(Xi, N, roughness);
        vec3 L  = normalize(2.0 * dot(V, H) * H - V);

        float NdotL = max(dot(N, L), 0.0);
        if(NdotL > 0.0)
        {
            prefilteredColor += sampleEnvironment(L) * NdotL;
            totalWeight      += NdotL;
        }
    }
    prefilteredColor = prefilteredColor / totalWeight;

    if (u_rgbm == 1) {
        float maxRGB = max(max(prefilteredColor.r, prefilteredColor.g), max(prefilteredColor.b, 1e-6));
        float A = clamp(maxRGB / 255.0, 0.0, 1.0);
        A = ceil(A * 255.0) / 255.0;
        FragColor = vec4(prefilteredColor / (A * 255.0), A);
    } else {
        FragColor = vec4(prefilteredColor, 1.0);
    }
}
