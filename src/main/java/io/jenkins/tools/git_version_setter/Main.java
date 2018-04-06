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

package io.jenkins.tools.git_version_setter;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;

@Component(role=AbstractMavenLifecycleParticipant.class, hint="git_version_setter")
public class Main extends AbstractMavenLifecycleParticipant {

    @Requirement
    private Logger log;

    @Override
    public void afterSessionStart(MavenSession session) throws MavenExecutionException {
        List<String> profiles = session.getRequest().getActiveProfiles();
        if (profiles.contains("incrementals") && profiles.contains("jenkins-release")) {
            Properties props = session.getRequest().getUserProperties();
            if (!props.containsKey("changelist")) {
                log.info("Setting the `changelist` property");
                File dir = session.getRequest().getMultiModuleProjectDirectory();
                log.debug("running in " + dir);
                String hash;
                int count = 0;
                try (Git git = Git.open(dir)) {
                    if (!git.status().call().isClean()) {
                        log.warn("Git repository seems to be dirty; commit before deploying");
                    }
                    Repository repo = git.getRepository();
                    ObjectId head = repo.resolve("HEAD");
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
                log.info("Setting: -Dchangelist=" + value);
                props.setProperty("changelist", value);
            } else {
                log.info("Declining to override the `changelist` property");
            }
        } else {
            log.debug("Skipping Git version setting unless run in -Pincrementals,jenkins-release");
        }
    }

}
