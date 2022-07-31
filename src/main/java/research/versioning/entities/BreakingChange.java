package versioning.entities;

import java.util.HashSet;
import java.util.Set;

public class BreakingChange {
    public int incursions;
    public Set<Method> incursing;

    public BreakingChange() {
        this.incursions = 0;
        this.incursing = new HashSet<>();
    }

    @Override
    public String toString() {
        return "{ " + this.incursions + " incursions in " + this.incursing;
    }
}