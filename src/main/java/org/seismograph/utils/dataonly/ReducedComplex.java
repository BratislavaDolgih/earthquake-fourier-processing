package org.seismograph.utils.dataonly;

import java.util.Objects;

public record ReducedComplex(double reality, double imaginary) {

    public ReducedComplex add(ReducedComplex other) {
        return new ReducedComplex(this.reality + other.reality,
                this.imaginary + other.imaginary);
    }

    public ReducedComplex sub(ReducedComplex other) {
        return new ReducedComplex(this.reality - other.reality,
                this.imaginary - other.imaginary);
    }

    public ReducedComplex mul(ReducedComplex other) {
        return new ReducedComplex(
                this.reality * other.reality - this.imaginary * other.imaginary,
                this.reality * other.imaginary + this.imaginary * other.reality
        );
    }

    @Override public String toString() {
        return String.format("(%.3f %s %.3fi)",
                this.reality, (imaginary < 0 ? "-" : "+"), this.imaginary);
    }

    @Override public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ReducedComplex that = (ReducedComplex) o;
        return Double.compare(reality, that.reality) == 0 && Double.compare(imaginary, that.imaginary) == 0;
    }

    @Override public int hashCode() {
        return Objects.hash(reality, imaginary);
    }
}
