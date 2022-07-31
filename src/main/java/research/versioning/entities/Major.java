package versioning.entities;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.util.Objects;

import static versioning.AnalysisHandler.getMajor;

public class Major {
    public Long packageId;
    public int majorVersion;
    public int numberOfMethods;
    public String packageName;

    public Major(Long packageId, DefaultArtifactVersion version, int numberOfMethods, String packageName) {
        this.packageId = packageId;
        this.majorVersion = getMajor(version.toString());
        this.numberOfMethods = numberOfMethods;
        this.packageName = packageName;
    }

    @Override
    public boolean equals(Object o) {
        return this.packageId.equals(((Major) o).packageId) && this.majorVersion == ((Major) o).majorVersion;
    }

    @Override
    public int hashCode() {
        return Objects.hash(packageId, majorVersion);
    }

    @Override
    public String toString() {
        return this.packageName + ":" + this.majorVersion;
    }
}
