package se.grynna.pager.db;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigValue implements Serializable {
    private Object value;

    public ConfigValue(String value) {
        this.value = value;
    }

    public ConfigValue(Integer value) {
        this.value = value;
    }

    public String getStringValue(String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }

    public int getIntValue(int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
