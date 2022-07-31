package versioning;

import eu.f4sten.pomanalyzer.data.MavenId;
import eu.fasten.core.dbconnectors.PostgresConnector;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jooq.*;
import versioning.entities.BreakingChange;
import versioning.entities.Major;
import versioning.entities.Method;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

import static coordinates.CoordsProcessor.getExpandedCoords;
import static versioning.AnalysisHandler.getMajor;
import static versioning.AnalysisHandler.getOldCallableIds;

public class BreakingChangeCalculator {
    /**
     * Gets DSLContext, requires *FASTEN_DBPASS* environment variable in run configuration.
     *
     * @return the database context.
     * @throws Exception if it can not connect.
     */
    private static DSLContext getDbContext() throws Exception {
        return PostgresConnector.getDSLContext("jdbc:postgresql://localhost:5432/fasten_java",
                "fasten", false);
    }

    /**
     * Runs the Java program.
     * @param args: 0: String: Maven coordinates file
     *              1: String: Violations output location
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        DSLContext context = getDbContext();

        List<MavenId> coords = getExpandedCoords(Paths.get("").toAbsolutePath() + "/" + args[0], context);
        Set<Result<Record5<String, Long, String, String, Long>>> results = AnalysisHandler.findMethods(context, coords);

        Map<String, Map<String, PriorityQueue<Method>>> packageIdMap = AnalysisHandler.createPackageIdMap(results);
        Map<Long, Set<DefaultArtifactVersion>> versionsPerPackageId = AnalysisHandler.getAllVersions(packageIdMap);
        Map<Long, Integer> methodsPerVersion = AnalysisHandler.getMethodsPerVersion(results);

        System.out.println("Finished retrieval of required data. Beginning illegal API extensions calculation.");
        Map<Major, BreakingChange> extensions = calculateMethodAddition(start, packageIdMap, versionsPerPackageId, methodsPerVersion);
        System.out.println("Finished illegal API extensions calculation. Beginning breaking change calculation.");
        Map<Major, BreakingChange> bc = calculateMethodRemove(start, packageIdMap, versionsPerPackageId, methodsPerVersion);

        getOldCallableIds(context, bc, args[0]);

        System.out.println("Finished breaking change calculation. Beginning file writing.");
        writeBreakingChangesToFile(bc, args[1], "breaking_changes.txt");
        writeBreakingChangesToFile(extensions, args[1], "api_extensions.txt");
    }

    public static Map<Major, BreakingChange> calculateMethodAddition(long start, Map<String, Map<String, PriorityQueue<Method>>> packageIdMap,
                                               Map<Long, Set<DefaultArtifactVersion>> versionsPerPackageId, Map<Long, Integer> methodsPerVersion) {

        Map<Major, BreakingChange> incursions = new HashMap<>();

        for (Map<String, PriorityQueue<Method>> methods : packageIdMap.values()) {
            for (PriorityQueue<Method> versions : methods.values()) {
                PriorityQueue<Method> reverse = new PriorityQueue<>(Collections.reverseOrder());
                reverse.addAll(versions);

                while (!reverse.isEmpty()) {
                    Method newest = reverse.poll();

                    PriorityQueue<DefaultArtifactVersion> lowerVersionsWoWMethod = new PriorityQueue<>(Collections.reverseOrder());

                    // Identify all versions that have been released, which have a version number higher than the number at
                    // which the method was introduced. Note that major releases don't count.
                    for (DefaultArtifactVersion version : versionsPerPackageId.get(newest.packageId)) {
                        if (version.getMajorVersion() == newest.version.getMajorVersion() &&
                                version.getMinorVersion() == newest.version.getMinorVersion() &&
                                version.compareTo(newest.version) < 0) {
                            lowerVersionsWoWMethod.add(version);
                        }
                    }
                    calculateViolations(reverse, lowerVersionsWoWMethod, incursions, newest, methodsPerVersion);
                }
            }
        }
        System.out.println(incursions);
        System.out.println("Execution time: " + (System.nanoTime() - start) / 1000000 + "ms");

        return incursions;
    }

    public static Map<Major, BreakingChange> calculateMethodRemove(long start, Map<String, Map<String, PriorityQueue<Method>>> packageIdMap,
                                             Map<Long, Set<DefaultArtifactVersion>> versionsPerPackageId, Map<Long, Integer> methodsPerVersion) {
        Map<Major, BreakingChange> incursions = new HashMap<>();

        for (Map<String, PriorityQueue<Method>> methods : packageIdMap.values()) {
            for (PriorityQueue<Method> versions : methods.values()) {
                while (!versions.isEmpty()) {
                    Method oldest = versions.poll();

                    PriorityQueue<DefaultArtifactVersion> higherVersionsWoWMethod = new PriorityQueue<>();

                    // Identify all versions that have been released, which have a version number higher than the number at
                    // which the method was introduced. Note that major releases don't count.
                    for (DefaultArtifactVersion version : versionsPerPackageId.get(oldest.packageId)) {
                        if (getMajor(version.toString()) == getMajor(oldest.version.toString()) &&
                                version.compareTo(oldest.version) > 0) {
                            higherVersionsWoWMethod.add(version);
                        }
                    }
                    calculateViolations(versions, higherVersionsWoWMethod, incursions, oldest, methodsPerVersion);
                }
            }

        }
        System.out.println(incursions);
        System.out.println("Execution time: " + (System.nanoTime() - start) / 1000000 + "ms");

        return incursions;
    }

    public static void calculateViolations(PriorityQueue<Method> versions,
                                           PriorityQueue<DefaultArtifactVersion> subsequentVersionsWoWMethod,
                                           Map<Major, BreakingChange> violations, Method current,
                                           Map<Long, Integer> methodsPerVersion) {
        Major major = new Major(current.packageId, current.version,
                methodsPerVersion.get(current.packageId), current.packageName);
        violations.putIfAbsent(major, new BreakingChange());

        if (versions.isEmpty()) {
            if (!subsequentVersionsWoWMethod.isEmpty()) {
                BreakingChange incursion = violations.get(major);
                incursion.incursing.add(current);
                incursion.incursions++;
            }
            return;
        }

        DefaultArtifactVersion oneHigherWMethod = versions.peek().version;

        // If no versions exist higher than the current version, we will check the next method.
        if (subsequentVersionsWoWMethod.isEmpty()) return;

        // If the next version featuring the method does not equal the absolute higher version,
        // there was an incursion.
        DefaultArtifactVersion oneHigherWoWMethod = subsequentVersionsWoWMethod.poll();
        if (!oneHigherWoWMethod.equals(oneHigherWMethod)) {
            BreakingChange incursion = violations.get(major);
            incursion.incursing.add(current);
            incursion.incursions++;
        }
    }

    public static void writeBreakingChangesToFile(Map<Major, BreakingChange> incursions, String dir, String filename) throws IOException {
        FileWriter fw = new FileWriter(Paths.get("").toAbsolutePath() + "/" + dir + "/" + filename);
        fw.write("Skip the first line when parsing this file. The format of this file is as follows: groupId:artifactId:majorVersion:#violations/#totalMethods:[callable.IDs with BC]\n");
        for (Major major : incursions.keySet()) {
            fw.write(major + ":" + incursions.get(major).incursions + "/" + major.numberOfMethods + ":" + incursions.get(major).incursing + "\n");
        }
        fw.close();
    }

    public static void writeCallableIdsToFile(Set<Result<Record5<String, Long, String, String, Long>>> results) throws IOException {
        FileWriter fw = new FileWriter(Paths.get("").toAbsolutePath()+ "/src/main/resources/callables.txt");
        for (Result<Record5<String, Long, String, String, Long>> result : results) {
            for (Record5<String, Long, String, String, Long> record : result) {
                fw.write(record.value5().toString() + ",");
            }
        }
        fw.close();
    }
}
