package featurecat.lizzie.analysis.gtpconfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Parsed, client-owned configuration schema exposed by an optional GTP extension. */
public final class GtpConfigurationSchema {
  private final String protocol;
  private final int version;
  private final List<Field> fields;
  private final JSONObject selected;
  private final JSONObject effective;

  private GtpConfigurationSchema(
      String protocol, int version, List<Field> fields, JSONObject selected, JSONObject effective) {
    this.protocol = protocol;
    this.version = version;
    this.fields = Collections.unmodifiableList(new ArrayList<Field>(fields));
    this.selected = copy(selected);
    this.effective = copy(effective);
  }

  public static GtpConfigurationSchema parse(JSONObject payload) {
    Objects.requireNonNull(payload, "payload");
    String protocol = payload.optString("protocol", "").trim();
    int version = payload.optInt("version", -1);
    if (protocol.isEmpty() || version < 1) {
      throw new IllegalArgumentException("Invalid GTP configuration protocol metadata");
    }
    if (!"atomic".equalsIgnoreCase(payload.optString("batchSemantics", ""))) {
      throw new IllegalArgumentException("GTP configuration updates must be atomic");
    }
    if (!"client".equalsIgnoreCase(payload.optString("persistenceOwner", ""))) {
      throw new IllegalArgumentException("GTP configuration profiles must be client-owned");
    }

    JSONArray fieldArray = payload.optJSONArray("fields");
    if (fieldArray == null || fieldArray.length() == 0) {
      throw new IllegalArgumentException("GTP configuration schema contains no fields");
    }
    List<Field> fields = new ArrayList<Field>();
    Set<String> names = new HashSet<String>();
    for (int i = 0; i < fieldArray.length(); i++) {
      Field field = Field.parse(fieldArray.getJSONObject(i));
      if (!names.add(field.name())) {
        throw new IllegalArgumentException("Duplicate GTP configuration field: " + field.name());
      }
      fields.add(field);
    }

    JSONObject state = payload.optJSONObject("state");
    JSONObject selected = state == null ? new JSONObject() : state.optJSONObject("selected");
    JSONObject effective = state == null ? new JSONObject() : state.optJSONObject("effective");
    return new GtpConfigurationSchema(
        protocol,
        version,
        fields,
        selected == null ? new JSONObject() : selected,
        effective == null ? new JSONObject() : effective);
  }

  public String protocol() {
    return protocol;
  }

  public int version() {
    return version;
  }

  public List<Field> fields() {
    return fields;
  }

  public JSONObject selected() {
    return copy(selected);
  }

  public JSONObject effective() {
    return copy(effective);
  }

  public JSONObject valuesWithSavedProfile(JSONObject savedProfile) {
    JSONObject values = selected();
    for (Field field : fields) {
      if (!values.has(field.name()) || !field.accepts(values.opt(field.name()))) {
        values.put(field.name(), field.defaultValue());
      }
    }
    if (savedProfile == null) {
      return values;
    }
    for (String key : savedProfile.keySet()) {
      Field field = field(key);
      if (field != null && field.accepts(savedProfile.opt(key))) {
        values.put(key, savedProfile.get(key));
      }
    }
    return values;
  }

  public boolean hasField(String name) {
    return field(name) != null;
  }

  public Field field(String name) {
    for (Field field : fields) {
      if (field.name().equals(name)) {
        return field;
      }
    }
    return null;
  }

  private static JSONObject copy(JSONObject value) {
    return value == null ? new JSONObject() : new JSONObject(value.toString());
  }

  public static final class Field {
    private final String name;
    private final String type;
    private final String group;
    private final Object defaultValue;
    private final Double minimum;
    private final Double maximum;
    private final List<String> enumValues;
    private final String activeWhen;
    private final String apply;
    private final boolean requiresRestart;

    private Field(
        String name,
        String type,
        String group,
        Object defaultValue,
        Double minimum,
        Double maximum,
        List<String> enumValues,
        String activeWhen,
        String apply,
        boolean requiresRestart) {
      this.name = name;
      this.type = type;
      this.group = group;
      this.defaultValue = defaultValue;
      this.minimum = minimum;
      this.maximum = maximum;
      this.enumValues = Collections.unmodifiableList(new ArrayList<String>(enumValues));
      this.activeWhen = activeWhen;
      this.apply = apply;
      this.requiresRestart = requiresRestart;
    }

    private static Field parse(JSONObject payload) {
      String name = payload.optString("name", "").trim();
      String type = payload.optString("type", "").trim();
      if (name.isEmpty()
          || !("string".equals(type) || "integer".equals(type) || "number".equals(type))) {
        throw new IllegalArgumentException("Unsupported GTP configuration field: " + name);
      }
      String apply = payload.optString("apply", "").trim();
      if (!"next-search".equals(apply)) {
        throw new IllegalArgumentException("Unsupported GTP configuration apply phase: " + apply);
      }
      List<String> enumValues = new ArrayList<String>();
      JSONArray values = payload.optJSONArray("enumValues");
      if (values != null) {
        for (int i = 0; i < values.length(); i++) {
          enumValues.add(values.getString(i));
        }
      }
      Field field =
          new Field(
              name,
              type,
              payload.optString("group", "advanced"),
              payload.has("defaultValue") ? payload.get("defaultValue") : "",
              numberOrNull(payload, "minimum"),
              numberOrNull(payload, "maximum"),
              enumValues,
              payload.optString("activeWhen", ""),
              apply,
              payload.optBoolean("requiresRestart", false));
      if (!field.accepts(field.defaultValue())) {
        throw new IllegalArgumentException("Invalid default value for GTP field: " + name);
      }
      if (field.requiresRestart()) {
        throw new IllegalArgumentException(
            "Restart-required GTP configuration fields are not supported: " + name);
      }
      return field;
    }

    private static Double numberOrNull(JSONObject payload, String key) {
      return payload.has(key) && !payload.isNull(key) ? payload.getDouble(key) : null;
    }

    public String name() {
      return name;
    }

    public String type() {
      return type;
    }

    public String group() {
      return group;
    }

    public Object defaultValue() {
      return defaultValue;
    }

    public Double minimum() {
      return minimum;
    }

    public Double maximum() {
      return maximum;
    }

    public List<String> enumValues() {
      return enumValues;
    }

    public String activeWhen() {
      return activeWhen;
    }

    public boolean requiresRestart() {
      return requiresRestart;
    }

    public String apply() {
      return apply;
    }

    public boolean isBasic() {
      return "basic".equalsIgnoreCase(group);
    }

    public boolean isActive(JSONObject values) {
      if (activeWhen == null || activeWhen.isBlank()) {
        return true;
      }
      int separator = activeWhen.indexOf('=');
      if (separator <= 0 || separator == activeWhen.length() - 1) {
        return true;
      }
      String dependency = activeWhen.substring(0, separator).trim();
      String selectedValue = values.optString(dependency, "");
      String[] acceptedValues = activeWhen.substring(separator + 1).split("\\|");
      for (String accepted : acceptedValues) {
        if (accepted.trim().equalsIgnoreCase(selectedValue)) {
          return true;
        }
      }
      return false;
    }

    public boolean accepts(Object value) {
      if (value == null || JSONObject.NULL.equals(value)) {
        return false;
      }
      if ("string".equals(type)) {
        if (!(value instanceof String)) {
          return false;
        }
        return enumValues.isEmpty() || enumValues.contains(value.toString());
      }
      if (!(value instanceof Number)) {
        return false;
      }
      double number = ((Number) value).doubleValue();
      if (!Double.isFinite(number) || ("integer".equals(type) && number != Math.rint(number))) {
        return false;
      }
      return (minimum == null || number >= minimum) && (maximum == null || number <= maximum);
    }
  }
}
