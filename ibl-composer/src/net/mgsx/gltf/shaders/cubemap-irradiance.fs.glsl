// from https://learnopengl.com/PBR/IBL/Diffuse-irradiance
#version 330 core
out vec4 FragColor;
in vec3 localPos;

uniform samplerCube environmentMap;

const float PI = 3.14159265359;

uniform float sampleDelta;

uniform int u_rgbm;

vec3 sampleEnvironment(vec3 dir) {
    vec4 color = texture(environmentMap, dir);
    if (u_rgbm == 1) {
        return color.rgb * color.a * 255.0;
    }
    return color.rgb;
}

void main()
{
    // the sample direction equals the hemisphere's orientation
    vec3 normal = normalize(localPos);

	vec3 irradiance = vec3(0.0);

	vec3 up    = vec3(0.0, 1.0, 0.0);
	vec3 right = cross(up, normal);
	up    = cross(normal, right);

	float nrSamples = 0.0;
	for(float phi = 0.0; phi < 2.0 * PI; phi += sampleDelta)
	{
	    for(float theta = 0.0; theta < 0.5 * PI; theta += sampleDelta)
	    {
	        // spherical to cartesian (in tangent space)
	        vec3 tangentSample = vec3(sin(theta) * cos(phi),  sin(theta) * sin(phi), cos(theta));
	        // tangent space to world
	        vec3 sampleVec = tangentSample.x * right + tangentSample.y * up + tangentSample.z * normal;

	        irradiance += sampleEnvironment(sampleVec) * cos(theta) * sin(theta);
	        nrSamples++;
	    }
	}
	irradiance = PI * irradiance * (1.0 / float(nrSamples));

    if (u_rgbm == 1) {
        float maxRGB = max(max(irradiance.r, irradiance.g), max(irradiance.b, 1e-6));
        float A = clamp(maxRGB / 255.0, 0.0, 1.0);
        A = ceil(A * 255.0) / 255.0;
        FragColor = vec4(irradiance / (A * 255.0), A);
    } else {
        FragColor = vec4(irradiance, 1.0);
    }
}
