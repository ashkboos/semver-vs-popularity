package versioning;

import coordinates.CoordsProcessor;
import eu.f4sten.pomanalyzer.data.MavenId;
import eu.fasten.core.data.metadatadb.codegen.enums.Access;
import eu.fasten.core.data.metadatadb.codegen.tables.Callables;
import eu.fasten.core.data.metadatadb.codegen.tables.PackageVersions;
import eu.fasten.core.data.metadatadb.codegen.tables.Packages;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.NotNull;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Record5;
import org.jooq.Result;
import org.jooq.impl.DSL;
import versioning.entities.BreakingChange;
import versioning.entities.Major;
import versioning.entities.Method;

import java.io.FileNotFoundException;
import java.nio.file.Paths;
import java.util.*;

public class AnalysisHandler {

    /**
     * Puts together a map, which maps the ID of a package to a map containing the method string and a priority
     * queue which contains all the method records.
     * @param results
     * @return
     */
    public static Map<String, Map<String, PriorityQueue<Method>>> createPackageIdMap(Set<Result<Record5<String, Long, String, String, Long>>> results) {
        Map<String, Map<String, PriorityQueue<Method>>> packageIdMap = new HashMap<>();

        //           Method, PackageID, Version, Name
        for (Result<Record5<String, Long, String, String, Long>> result : results) {
            for (Record5<String, Long, String, String, Long> record : result) {
                String method = record.value1();
                Long packageId = record.value2();
                String version = record.value3();
                int major = getMajor(version);
                String key = packageId + String.valueOf(major);
                packageIdMap.computeIfAbsent(key, k -> new HashMap<>());
                packageIdMap.get(key).computeIfAbsent(method, k -> new PriorityQueue<>());
                packageIdMap.get(key).get(method).add(new Method(version, method, packageId, record.value4(), record.value5()));
            }
        }

        return packageIdMap;
    }

    public static int getMajor(String version) {
        int major = new DefaultArtifactVersion(version).getMajorVersion();
        if (major == 0) {
            return Integer.parseInt(version.split("\\.")[0]);
        }
        return major;
    }

    /**
     * Gets all the versions of every package, and returns those in a map.
     * @param packageIdMap
     * @return
     */
    public static Map<Long, Set<DefaultArtifactVersion>> getAllVersions(Map<String, Map<String, PriorityQueue<Method>>> packageIdMap) {
        Map<Long, Set<DefaultArtifactVersion>> versionsPerPackageId = new HashMap<>();

        for (Map<String, PriorityQueue<Method>> methods : packageIdMap.values()) {
            for (PriorityQueue<Method> versions : methods.values()) {
                for (Method version : versions) {
                    versionsPerPackageId.computeIfAbsent(version.packageId, k -> new HashSet<>());
                    versionsPerPackageId.get(version.packageId).add(version.version);
                }
            }
        }
        return versionsPerPackageId;
    }

    public static @NotNull
    Set<Result<Record5<String, Long, String, String, Long>>> findMethods(DSLContext context, List<MavenId> mavenIds) {
        Set<Result<Record5<String, Long, String, String, Long>>> results = new HashSet<Result<Record5<String, Long, String, String, Long>>>();
        for (MavenId mavenId : mavenIds) {
            Result<Record5<String, Long, String, String, Long>> result = findMethodsPerArtifact(context, mavenId);
            if (result.size() > 0) {
                results.add(result);
            }
        }
        return results;
    }

    public static void getOldCallableIds(DSLContext context, Map<Major, BreakingChange> violations, String path) throws FileNotFoundException {
        List<MavenId> coords = CoordsProcessor.readCoordsFile(path);

        for (BreakingChange bc : violations.values()) {
            for (Method method : bc.incursing) {
                method.callableId = getOldCallableId(context, method, coords);
            }
        }
    }

    public static Long getOldCallableId(DSLContext context, Method method, List<MavenId> coords) {
        String version = "";

        for (MavenId mavenId : coords) {
            if (mavenId.asCoordinate().startsWith(method.packageName)) {
                version = mavenId.version;
            }
        }

        Result<Record1<Long>> result = context.select(Callables.CALLABLES.ID)
                .from("callables")
                .join("modules").on(DSL.field("module_id").eq(DSL.field("modules.id")))
                .join("package_versions").on(DSL.field("package_version_id").eq(DSL.field("package_versions.id")))
                .join("packages").on(DSL.field("package_versions.package_id").eq(DSL.field("packages.id")))
                .where(DSL.field("packages.package_name").eq(method.packageName))
                .and(DSL.field("package_versions.version").eq(version))
                .and(DSL.field("callables.fasten_uri").eq(method.method))
                .fetch();

        return result.size() == 0 ? -1 : result.get(0).value1();
    }

    /**
     * Runs the SQL query which finds every method of every package in the database.
     * @param context
     * @return
     */
    public static @NotNull
    Result<Record5<String, Long, String, String, Long>> findMethodsPerArtifact(DSLContext context, MavenId mavenId) {
        return context.select(Callables.CALLABLES.FASTEN_URI, PackageVersions.PACKAGE_VERSIONS.PACKAGE_ID,
                        PackageVersions.PACKAGE_VERSIONS.VERSION, Packages.PACKAGES.PACKAGE_NAME, Callables.CALLABLES.ID)
                .from("callables")
                .join("modules").on(DSL.field("module_id").eq(DSL.field("modules.id")))
                .join("package_versions").on(DSL.field("package_version_id").eq(DSL.field("package_versions.id")))
                .join("packages").on(DSL.field("package_versions.package_id").eq(DSL.field("packages.id")))
                .and(DSL.field("packages.package_name").eq(mavenId.groupId+":"+mavenId.artifactId))
                .and(DSL.field("package_versions.version").eq(mavenId.version))
                .and(DSL.field("callables.access").eq(Access.public_))
                .and(DSL.field("defined").eq(true))
                .and(DSL.field("callables.is_internal_call").eq(true))
                .and(DSL.field("callables.fasten_uri").notLike("%$Lambda.%"))
                .fetch();
    }

    //                                                                                               Method, PackageID, Version
    public static Map<Long, Integer> getMethodsPerVersion(Set<Result<Record5<String, Long, String, String, Long>>> results) {
        Map<Long, Set<String>> uris = new HashMap<>();

        for (Result<Record5<String, Long, String, String, Long>> result : results) {
            for (Record5<String, Long, String, String, Long> record : result) {
                Long packageId = record.value2();
                DefaultArtifactVersion version = new DefaultArtifactVersion(record.value3());
                uris.putIfAbsent(packageId, new HashSet<>());
                uris.get(packageId).add(record.value1());
            }
        }

        Map<Long, Integer> methodCount = new HashMap<>();
        for (Long artifact : uris.keySet()) {
            methodCount.put(artifact, uris.get(artifact).size());
        }
        return methodCount;
    }
}
