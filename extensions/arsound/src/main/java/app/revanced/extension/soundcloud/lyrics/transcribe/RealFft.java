package app.revanced.extension.soundcloud.lyrics.transcribe;

/**
 * The discrete Fourier transform of real frames, for a length that is a power of two or three times one (the voice
 * separation model works on frames of 6144 = 3 · 2048 samples). The three thirds of a frame are transformed with an
 * ordinary radix-2 FFT and joined. Pure arithmetic in doubles; one object per length, not shared between threads.
 * <p>
 * Used by {@link MdxStft}.
 */
final class RealFft {
    private final int n;
    /** Length of the radix-2 parts: n, or n / 3. */
    private final int m;
    private final boolean thirds;
    private final int[] reversed;
    private final double[] cosM;
    private final double[] sinM;
    private final double[] cosN;
    private final double[] sinN;
    private final double[] re;
    private final double[] im;
    private final double[][] partRe;
    private final double[][] partIm;

    RealFft(int n) {
        this.n = n;
        thirds = n % 3 == 0;
        m = thirds ? n / 3 : n;
        if (m < 2 || (m & (m - 1)) != 0) throw new IllegalArgumentException("Length " + n + " is not 2^k or 3 * 2^k");
        int bits = Integer.numberOfTrailingZeros(m);
        reversed = new int[m];
        for (int i = 0; i < m; i++) reversed[i] = Integer.reverse(i) >>> (32 - bits);
        cosM = new double[m / 2];
        sinM = new double[m / 2];
        for (int i = 0; i < m / 2; i++) {
            cosM[i] = Math.cos(-2 * Math.PI * i / m);
            sinM[i] = Math.sin(-2 * Math.PI * i / m);
        }
        cosN = new double[n];
        sinN = new double[n];
        for (int i = 0; i < n; i++) {
            cosN[i] = Math.cos(-2 * Math.PI * i / n);
            sinN[i] = Math.sin(-2 * Math.PI * i / n);
        }
        re = new double[n];
        im = new double[n];
        int parts = thirds ? 3 : 1;
        partRe = new double[parts][m];
        partIm = new double[parts][m];
    }

    int length() {
        return n;
    }

    /**
     * The spectrum of a real frame: bins 0 to n / 2.
     *
     * @param frame   n samples.
     * @param outRe   Receives the real parts at {@code offset} .. {@code offset + n / 2}.
     * @param outIm   Receives the imaginary parts at the same places.
     */
    void forward(double[] frame, float[] outRe, float[] outIm, int offset) {
        for (int i = 0; i < n; i++) {
            re[i] = frame[i];
            im[i] = 0;
        }
        transform();
        for (int k = 0; k <= n / 2; k++) {
            outRe[offset + k] = (float) re[k];
            outIm[offset + k] = (float) im[k];
        }
    }

    /**
     * The real frame of a spectrum (bins 0 to n / 2, the rest mirrored), divided by n like numpy's {@code irfft}.
     *
     * @param frame Receives n samples.
     */
    void inverse(float[] inRe, float[] inIm, int offset, double[] frame) {
        for (int k = 0; k <= n / 2; k++) {
            // The inverse is the forward transform of the conjugate, conjugated again.
            re[k] = inRe[offset + k];
            im[k] = -inIm[offset + k];
        }
        for (int k = n / 2 + 1; k < n; k++) {
            re[k] = inRe[offset + n - k];
            im[k] = inIm[offset + n - k];
        }
        // A real frame has no imaginary part at the first and the middle bin.
        im[0] = 0;
        im[n / 2] = 0;
        transform();
        for (int i = 0; i < n; i++) frame[i] = re[i] / n;
    }

    /** The complex DFT of {@link #re}/{@link #im} in place. */
    private void transform() {
        if (!thirds) {
            radix2(re, im);
            return;
        }
        for (int part = 0; part < 3; part++) {
            double[] pr = partRe[part];
            double[] pi = partIm[part];
            for (int i = 0; i < m; i++) {
                pr[i] = re[3 * i + part];
                pi[i] = im[3 * i + part];
            }
            radix2(pr, pi);
        }
        // X[k] = F0[k mod m] + W^k F1[k mod m] + W^2k F2[k mod m], W = e^(-2 pi i / n).
        for (int k = 0; k < n; k++) {
            int j = k % m;
            int k2 = (2 * k) % n;
            double r1 = partRe[1][j] * cosN[k] - partIm[1][j] * sinN[k];
            double i1 = partRe[1][j] * sinN[k] + partIm[1][j] * cosN[k];
            double r2 = partRe[2][j] * cosN[k2] - partIm[2][j] * sinN[k2];
            double i2 = partRe[2][j] * sinN[k2] + partIm[2][j] * cosN[k2];
            re[k] = partRe[0][j] + r1 + r2;
            im[k] = partIm[0][j] + i1 + i2;
        }
    }

    /** Iterative radix-2 FFT of length m, in place. */
    private void radix2(double[] xr, double[] xi) {
        for (int i = 0; i < m; i++) {
            int j = reversed[i];
            if (j > i) {
                double t = xr[i];
                xr[i] = xr[j];
                xr[j] = t;
                t = xi[i];
                xi[i] = xi[j];
                xi[j] = t;
            }
        }
        for (int size = 2; size <= m; size <<= 1) {
            int half = size >> 1;
            int step = m / size;
            for (int start = 0; start < m; start += size) {
                for (int k = 0; k < half; k++) {
                    double wr = cosM[k * step];
                    double wi = sinM[k * step];
                    int a = start + k;
                    int b = a + half;
                    double tr = xr[b] * wr - xi[b] * wi;
                    double ti = xr[b] * wi + xi[b] * wr;
                    xr[b] = xr[a] - tr;
                    xi[b] = xi[a] - ti;
                    xr[a] += tr;
                    xi[a] += ti;
                }
            }
        }
    }
}
