package io.activated.release;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Read the latest semver tag reachable from HEAD, bump it by the requested {@code level}
 * (patch/minor/major, default minor), and create an annotated tag. Pushes only when
 * {@code release.push=true}, so a release is never cut by accident. Git is the source of truth —
 * nothing is written to the POM and no extra commits are made.
 *
 * <pre>
 *   mvn release:bump                          # minor  (default): v1.4.2 -&gt; v1.5.0
 *   mvn release:bump -Drelease.level=patch    #          v1.4.2 -&gt; v1.4.3
 *   mvn release:bump -Drelease.level=major    #          v1.4.2 -&gt; v2.0.0
 * </pre>
 */
@Mojo(name = "bump", requiresProject = false, threadSafe = true)
public class BumpMojo extends AbstractMojo {

    private static final Pattern SEMVER = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    File basedir;

    /** Which component to bump: patch, minor, or major. Defaults to minor. */
    @Parameter(property = "release.level", defaultValue = "minor")
    String level;

    @Parameter(property = "release.tagPrefix", defaultValue = "v")
    String tagPrefix;

    @Parameter(property = "release.remote", defaultValue = "origin")
    String remote;

    /** Push the new tag to the remote. Defaults to false so a release is never cut by accident;
     *  push explicitly with -Drelease.push=true (or `git push` the tag) to trigger CI. */
    @Parameter(property = "release.push", defaultValue = "false")
    boolean push;

    @Parameter(property = "release.dryRun", defaultValue = "false")
    boolean dryRun;

    /** Refuse to tag when the working tree has uncommitted changes. Set true to bypass. */
    @Parameter(property = "release.allowDirty", defaultValue = "false")
    boolean allowDirty;

    @Override
    public void execute() throws MojoExecutionException {
        String base = latestReachableTag();
        int major = 0, minor = 0, patch = 0;
        if (base != null) {
            Matcher m = SEMVER.matcher(stripPrefix(base));
            if (!m.matches()) {
                throw new MojoExecutionException(
                        "Latest tag '" + base + "' is not " + tagPrefix + "MAJOR.MINOR.PATCH");
            }
            major = Integer.parseInt(m.group(1));
            minor = Integer.parseInt(m.group(2));
            patch = Integer.parseInt(m.group(3));
        }

        int[] next = bump(major, minor, patch);
        String newTag = tagPrefix + next[0] + "." + next[1] + "." + next[2];

        if (tagExists(newTag)) {
            throw new MojoExecutionException("Tag " + newTag + " already exists");
        }

        getLog().info("Release (" + level + "): " + (base == null ? "(no tags yet)" : base)
                + "  ->  " + newTag);

        // Don't tag on top of uncommitted work — the tag would point at a commit that doesn't
        // reflect the working tree. `git status --porcelain` respects .gitignore.
        List<String> dirty = git(false, "status", "--porcelain");
        if (!dirty.isEmpty() && !allowDirty) {
            String msg = "Working tree is not clean (uncommitted changes):\n  "
                    + String.join("\n  ", dirty)
                    + "\nCommit or stash them first, or pass -Drelease.allowDirty=true.";
            if (dryRun) {
                getLog().warn(msg);
            } else {
                throw new MojoExecutionException(msg);
            }
        }

        if (dryRun) {
            getLog().info("[dryRun] would tag " + newTag + (push ? " and push to " + remote : ""));
            return;
        }

        git("tag", "-a", newTag, "-m", "Release " + newTag);
        getLog().info("Created annotated tag " + newTag);
        if (push) {
            git("push", remote, newTag);
            getLog().info("Pushed " + newTag + " to " + remote + " — CI can now build the release.");
        } else {
            getLog().info("release.push=false — push it yourself: git push " + remote + " " + newTag);
        }
    }

    /** Apply the requested bump level to the current version triple. */
    private int[] bump(int major, int minor, int patch) throws MojoExecutionException {
        switch (level.toLowerCase()) {
            case "patch":
                return new int[] {major, minor, patch + 1};
            case "minor":
                return new int[] {major, minor + 1, 0};
            case "major":
                return new int[] {major + 1, 0, 0};
            default:
                throw new MojoExecutionException(
                        "release.level must be patch, minor, or major (was: '" + level + "')");
        }
    }

    /** Latest MAJOR.MINOR.PATCH tag reachable from HEAD, or null if there are none. */
    private String latestReachableTag() throws MojoExecutionException {
        List<String> out = git(false,
                "describe", "--tags", "--abbrev=0", "--match", tagPrefix + "[0-9]*.[0-9]*.[0-9]*");
        return out.isEmpty() ? null : out.get(0).trim();
    }

    private boolean tagExists(String tag) throws MojoExecutionException {
        return !git(false, "tag", "--list", tag).isEmpty();
    }

    private String stripPrefix(String tag) {
        return tag.startsWith(tagPrefix) ? tag.substring(tagPrefix.length()) : tag;
    }

    /** Run git and fail the build on a non-zero exit. */
    private void git(String... args) throws MojoExecutionException {
        git(true, args);
    }

    /** Run git; on non-zero exit either throw (failOnError) or return no output. */
    private List<String> git(boolean failOnError, String... args) throws MojoExecutionException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String a : args) {
            cmd.add(a);
        }
        try {
            Process p = new ProcessBuilder(cmd)
                    .directory(basedir)
                    .redirectErrorStream(true)
                    .start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            }
            int code = p.waitFor();
            if (code != 0) {
                if (failOnError) {
                    throw new MojoExecutionException(
                            "git " + String.join(" ", args) + " failed (exit " + code + "):\n"
                                    + String.join("\n", lines));
                }
                return List.of();
            }
            return lines;
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to run git " + String.join(" ", args), e);
        }
    }
}
