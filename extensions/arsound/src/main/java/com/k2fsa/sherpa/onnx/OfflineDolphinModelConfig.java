// Vendored unmodified from https://github.com/k2-fsa/sherpa-onnx, tag v1.13.8 (Apache License 2.0, see NOTICE.txt).
// Copyright 2025 Xiaomi Corporation

package com.k2fsa.sherpa.onnx;

public class OfflineDolphinModelConfig {
    private final String model;

    private OfflineDolphinModelConfig(Builder builder) {
        this.model = builder.model;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getModel() {
        return model;
    }

    public static class Builder {
        private String model = "";

        public OfflineDolphinModelConfig build() {
            return new OfflineDolphinModelConfig(this);
        }

        public Builder setModel(String model) {
            this.model = model;
            return this;
        }
    }
}