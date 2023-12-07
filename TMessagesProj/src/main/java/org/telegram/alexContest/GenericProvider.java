package org.telegram.alexContest;

public interface GenericProvider<F, T> {
    T provide(F obj);
}
