package provider;

import provider.wz.DataType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A data node built in memory instead of read from the WZ XML. Used for skill levels past the data
 * (client.Skill generates levels up to Skill.MAX_SKILL_LEVEL), so StatEffect reads them like any real level.
 */
public final class GeneratedData implements Data {
    private final String name;
    private final DataType type;
    private final Object value;
    private final List<Data> children = new ArrayList<>();
    private GeneratedData parent;

    public GeneratedData(String name, DataType type, Object value) {
        this.name = name;
        this.type = type;
        this.value = value;
    }

    /** A folder node (an imgdir). */
    public static GeneratedData folder(String name) {
        return new GeneratedData(name, DataType.PROPERTY, null);
    }

    public GeneratedData add(GeneratedData child) {
        child.parent = this;
        children.add(child);
        return this;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public DataType getType() {
        return type;
    }

    @Override
    public List<Data> getChildren() {
        return new ArrayList<>(children);
    }

    @Override
    public Data getChildByPath(String path) {
        Data at = this;
        for (String part : path.split("/")) {
            if (part.equals("..")) {
                at = at.getParent() instanceof Data p ? p : null;
            } else {
                Data next = null;
                for (Data child : at.getChildren()) {
                    if (child.getName().equals(part)) {
                        next = child;
                        break;
                    }
                }
                at = next;
            }
            if (at == null) {
                return null;
            }
        }
        return at;
    }

    @Override
    public Object getData() {
        return value;
    }

    @Override
    public DataEntity getParent() {
        return parent;
    }

    @Override
    public Iterator<Data> iterator() {
        return getChildren().iterator();
    }
}
