package com.xckeji.bj.model;

import java.util.Random;

/** Perlin Noise 2D — 移植自 Flutter 版本的 perlin_noise.dart */
public class PerlinNoise {
    private final int[] perm;

    public PerlinNoise(int seed) {
        Random r = new Random(seed);
        int[] p = new int[512];
        perm = new int[512];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int tmp = p[i]; p[i] = p[j]; p[j] = tmp;
        }
        System.arraycopy(p, 0, perm, 0, 256);
        System.arraycopy(p, 0, perm, 256, 256);
    }

    public double noise2D(double x, double y) {
        int X = (int) Math.floor(x) & 255;
        int Y = (int) Math.floor(y) & 255;
        x -= Math.floor(x);
        y -= Math.floor(y);
        double u = fade(x), v = fade(y);
        int A = perm[X] + Y, AA = perm[A], AB = perm[A + 1];
        int B = perm[X + 1] + Y, BA = perm[B], BB = perm[B + 1];
        return lerp(v,
            lerp(u, grad(perm[AA], x, y, 0), grad(perm[BA], x - 1, y, 0)),
            lerp(u, grad(perm[AB], x, y - 1, 0), grad(perm[BB], x - 1, y - 1, 0)));
    }

    public double fractalNoise2D(double x, double y, int octaves, double persistence, double lacunarity) {
        double value = 0, amplitude = 1, frequency = 1, maxValue = 0;
        for (int i = 0; i < octaves; i++) {
            value += noise2D(x * frequency, y * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= persistence;
            frequency *= lacunarity;
        }
        return value / maxValue;
    }

    private double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private double lerp(double t, double a, double b) { return a + t * (b - a); }
    private double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y, v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
