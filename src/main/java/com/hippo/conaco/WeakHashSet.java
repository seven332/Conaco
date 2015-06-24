package com.hippo.conaco;

import android.support.annotation.NonNull;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.WeakHashMap;

public class WeakHashSet<E> implements Set<E> {

    private static final Object DUMMY_VALUE = new Object();

    private WeakHashMap<E, Object> map;

    public WeakHashSet() {
        map = new WeakHashMap<>();
    }

    public WeakHashSet(int size) {
        map = new WeakHashMap<>(size);
    }

    @Override
    public boolean add(E object) {
        Object oldValue = map.put(object, DUMMY_VALUE);
        return oldValue == null;
    }

    @Override
    public boolean addAll(@NonNull Collection<? extends E> collection) {
        boolean modified = false;
        for (E i : collection) {
            modified |= add(i);
        }
        return modified;
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public boolean contains(Object object) {
        return map.containsKey(object);
    }

    @Override
    public boolean containsAll(@NonNull Collection<?> collection) {
        return map.keySet().containsAll(collection);
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @NonNull
    @Override
    public Iterator<E> iterator() {
        return map.keySet().iterator();
    }

    @Override
    public boolean remove(Object object) {
        return map.remove(object) == DUMMY_VALUE;
    }

    @Override
    public boolean removeAll(@NonNull Collection<?> collection) {
        return map.keySet().removeAll(collection);
    }

    @Override
    public boolean retainAll(@NonNull Collection<?> collection) {
        return map.keySet().retainAll(collection);
    }

    @Override
    public int size() {
        return map.size();
    }

    @NonNull
    @Override
    public Object[] toArray() {
        return map.keySet().toArray();
    }

    @NonNull
    @Override
    public <T> T[] toArray(@NonNull T[] array) {
        return map.keySet().toArray(array);
    }
}
