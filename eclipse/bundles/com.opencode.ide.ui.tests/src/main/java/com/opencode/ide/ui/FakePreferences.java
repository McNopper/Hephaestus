package com.opencode.ide.ui;

import java.util.HashMap;
import java.util.Map;
import org.osgi.service.prefs.Preferences;

/**
 * Minimal OSGI {@link Preferences} fake shared by the ui.tests preference contracts (extracted
 * 2026-10-11 when U-066 joined AttentionPreferencesTest's copy - QUALITY.md: duplication is
 * eliminated by extraction, never tolerated): string-map storage with OSGi {@code getBoolean(key,
 * def)} semantics (default when absent) and a {@code flushes} counter for save() assertions; the
 * node machinery these features never use throws.
 */
public final class FakePreferences implements Preferences {

  private final Map<String, String> values = new HashMap<>();

  private int flushes;

  /**
   * @return how many times {@link #flush()} ran (save() assertions).
   */
  public int flushCount() {
    return flushes;
  }

  @Override
  public String get(String key, String def) {
    return values.getOrDefault(key, def);
  }

  @Override
  public void put(String key, String value) {
    values.put(key, value);
  }

  @Override
  public boolean getBoolean(String key, boolean def) {
    String value = values.get(key);
    return value == null ? def : Boolean.parseBoolean(value);
  }

  @Override
  public void putBoolean(String key, boolean value) {
    values.put(key, Boolean.toString(value));
  }

  @Override
  public void remove(String key) {
    values.remove(key);
  }

  @Override
  public void clear() {
    values.clear();
  }

  @Override
  public String[] keys() {
    return values.keySet().toArray(new String[0]);
  }

  @Override
  public void flush() {
    flushes++;
  }

  @Override
  public String absolutePath() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String[] childrenNames() {
    throw new UnsupportedOperationException();
  }

  @Override
  public byte[] getByteArray(String key, byte[] def) {
    throw new UnsupportedOperationException();
  }

  @Override
  public double getDouble(String key, double def) {
    throw new UnsupportedOperationException();
  }

  @Override
  public float getFloat(String key, float def) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getInt(String key, int def) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getLong(String key, long def) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String name() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Preferences node(String pathName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean nodeExists(String pathName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Preferences parent() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void putByteArray(String key, byte[] value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void putDouble(String key, double value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void putFloat(String key, float value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void putInt(String key, int value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void putLong(String key, long value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeNode() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void sync() {
    throw new UnsupportedOperationException();
  }
}
