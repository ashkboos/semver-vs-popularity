package versioning.entities;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

public class Method implements Comparable<Method> {
    public DefaultArtifactVersion version;
    public String method;
    public Long packageId;
    public String packageName;
    public Long callableId;

    @Override
    public String toString() {
        return this.callableId.toString() + "/" + this.version.toString();
    }

    public Method(String version, String method, Long packageId, String packageName, Long callableId) {
        this.version = new DefaultArtifactVersion(version);
        this.method = method;
        this.packageId = packageId;
        this.packageName = packageName;
        this.callableId = callableId;
    }

    /**
     * If the version number of this is smaller than the other's version number, it will return -1.
     * @param o
     * @return
     */
    @Override
    public int compareTo(Method o) {
        return this.version.compareTo(o.version);
    }
}