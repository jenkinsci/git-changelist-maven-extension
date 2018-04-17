/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.tools.git_changelist_maven_extension;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Sets a {@code changelist} property to a value based on the Git checkout.
 * {@code -Dset.changelist} then becomes equivalent to:
 * {@code -Dchangelist=-rc$(git rev-list --first-parent --count HEAD).$(git rev-parse --short=12 HEAD)}
 * <p>Also does the equivalent of: {@code -DscmTag=$(git rev-parse HEAD)}
 * @see <a href="https://maven.apache.org/maven-ci-friendly.html">Maven CI Friendly Versions</a>
 * @see <a href="https://maven.apache.org/docs/3.3.1/release-notes.html#Core_Extensions">Core Extensions</a>
 */
@Component(role=AbstractMavenLifecycleParticipant.class, hint="git-changelist-maven-extension")
public class Main extends AbstractMavenLifecycleParticipant {

    @Requirement
    private Logger log;

    @Override
    public void afterSessionStart(MavenSession session) throws MavenExecutionException {
        Properties props = session.getRequest().getUserProperties();
        if ("true".equals(props.getProperty("set.changelist"))) {
            if (!props.containsKey("changelist") && !props.containsKey("scmTag")) {
                File dir = session.getRequest().getMultiModuleProjectDirectory();
                log.debug("running in " + dir);
                String fullHash, hash;
                int count = 0;
                try (Git git = Git.open(dir)) {
                    Status status = git.status().call();
                    if (!status.isClean()) {
                        // Could consider instead making this append a timestamp baased on the most recent file modification.
                        Set<String> paths = new TreeSet<>(status.getUncommittedChanges());
                        paths.addAll(status.getUntracked());
                        // Note that `git st` does not care about untracked _folders_ so long as there are no relevant _files_ inside them.
                        throw new MavenExecutionException("Make sure `git status -s` is empty before using -Dset.changelist: " + paths, (Throwable) null);
                    }
                    Repository repo = git.getRepository();
                    ObjectId head = repo.resolve("HEAD");
                    fullHash = head.name();
                    hash = head.abbreviate(12).name();
                    try (RevWalk walk = new RevWalk(repo)) {
                        RevCommit c = walk.parseCommit(head);
                        // https://stackoverflow.com/a/33054511/12916 RevWalk does not seem to provide any easy equivalent to --first-parent, so cannot simply walk.markStart(c) and iterate
                        while (c != null) {
                            count++;
                            if (log.isDebugEnabled()) {
                                log.debug("found commit: " + c.getShortMessage());
                            }
                            if (c.getParentCount() == 0) {
                                c = null;
                            } else {
                                c = walk.parseCommit(c.getParent(0));
                            }
                        }
                    }
                } catch (IOException | GitAPIException x) {
                    throw new MavenExecutionException("Git operations failed", x);
                }
                // should match: -rc$(git rev-list --first-parent --count HEAD).$(git rev-parse --short=12 HEAD)
                String value = "-rc" + count + "." + hash;
                log.info("Setting: -Dchangelist=" + value + " -DscmTag=" + fullHash);
                props.setProperty("changelist", value);
                props.setProperty("scmTag", fullHash);
            } else {
                log.info("Declining to override the `changelist` or `scmTag` properties");
            }
        } else {
            log.debug("Skipping Git version setting unless run with -Dset.changelist");
        }
    }

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        Properties props = session.getRequest().getUserProperties();
        if ("true".equals(props.getProperty("set.changelist"))) {
            String changelist = props.getProperty("changelist");
            for (MavenProject project : session.getProjects()) {
                String version = project.getVersion();
                if (!version.contains(changelist)) {
                    log.warn(project.getId() + " does not seem to be including ${changelist} in its <version>");
                }
            }
        }
    }

}
