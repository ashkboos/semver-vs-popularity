package coordinates;

import eu.f4sten.mavencrawler.utils.FileReader;
import eu.f4sten.pomanalyzer.data.MavenId;
import eu.fasten.core.data.metadatadb.codegen.enums.Access;
import eu.fasten.core.data.metadatadb.codegen.tables.Callables;
import eu.fasten.core.data.metadatadb.codegen.tables.PackageVersions;
import eu.fasten.core.data.metadatadb.codegen.tables.Packages;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.impl.DSL;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

public class CoordsProcessor {
    public static List<MavenId> getExpandedCoords(String file, DSLContext context) throws Exception {
        List<MavenId> input = readCoordsFile(file);
        Set<MavenId> mavenIds = getVersionsOnServer(input, context);

        return extractAllVersions(input, mavenIds);
    }

    public static List<MavenId> extractAllVersions(List<MavenId> inputIds, Set<MavenId> maven) {
        List<MavenId> result = new ArrayList<>();
        for (MavenId currInput : inputIds) {
            for (MavenId mavenId : maven) {
                DefaultArtifactVersion inp_v = new DefaultArtifactVersion(currInput.version);
                DefaultArtifactVersion mav_v = new DefaultArtifactVersion(mavenId.version);
                if (currInput.groupId.equals(mavenId.groupId) && currInput.artifactId.equals(mavenId.artifactId) && (inp_v.compareTo(mav_v) <= 0)) {
                    MavenId toAdd = new MavenId();
                    toAdd.groupId = mavenId.groupId;
                    toAdd.artifactId = mavenId.artifactId;
                    toAdd.version = mavenId.version;
                    result.add(toAdd);
                }
            }
        }
        return result;
    }

    public static void writeCoordsFile(String path, List<MavenId> mavenIds) throws IOException {
        FileWriter fw = new FileWriter(path + "mvn.expanded_coords.txt");
        for (MavenId mavenId : mavenIds) {
            fw.write(mavenId.groupId + ":" + mavenId.artifactId + ":" + mavenId.version + "\n");
        }
        fw.close();
    }

    public static List<MavenId> readCoordsFile(String path) throws FileNotFoundException {
        Scanner sc = new Scanner(new File(path));
        sc.useDelimiter(":");
        List<MavenId> coords = new ArrayList<>();

        while (sc.hasNext()) {
            MavenId newPackage = new MavenId();
            String[] split = sc.nextLine().split(":");

            newPackage.groupId = split[0];
            newPackage.artifactId = split[1];
            newPackage.version = split[2];

            coords.add(newPackage);
        }
        sc.close();
        return coords;
    }
    
    public static Set<MavenId> getVersionsOnServer(List<MavenId> mavenIds, DSLContext context) {
        Set<MavenId> expandedIds = new HashSet<>();
        
        for (MavenId mavenId : mavenIds) {
            Result<Record1<String>> result = context.select(PackageVersions.PACKAGE_VERSIONS.VERSION)
                    .from("package_versions")
                    .join("packages").on(DSL.field("package_versions.package_id").eq(DSL.field("packages.id")))
                    .where(DSL.field("packages.package_name").eq(mavenId.groupId+":"+mavenId.artifactId))
                    .fetch();

            for (Record1<String> record : result) {
                MavenId toAdd = new MavenId();
                toAdd.groupId = mavenId.groupId;
                toAdd.artifactId = mavenId.artifactId;
                toAdd.version = record.value1();
                expandedIds.add(toAdd);
            }
        }
        return expandedIds;
    }
}
