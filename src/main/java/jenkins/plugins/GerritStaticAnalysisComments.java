package jenkins.plugins;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.sonyericsson.hudson.plugins.gerrit.trigger.gerritnotifier.GerritMessageProvider;
import com.sonyericsson.hudson.plugins.gerrit.trigger.gerritnotifier.rest.object.CommentedFile;
import com.sonyericsson.hudson.plugins.gerrit.trigger.gerritnotifier.rest.object.LineComment;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.Hudson;
import hudson.model.TaskListener;
import hudson.plugins.analysis.collector.AnalysisResult;
import hudson.plugins.analysis.collector.AnalysisResultAction;
import hudson.plugins.analysis.core.AbstractResultAction;
import hudson.plugins.analysis.core.AnnotationDifferencer;
import hudson.plugins.analysis.core.BuildResult;
import hudson.plugins.analysis.core.ParserResult;
import hudson.plugins.analysis.util.model.FileAnnotation;
import hudson.plugins.git.util.BuildData;
import hudson.remoting.VirtualChannel;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Extension
public class GerritStaticAnalysisComments extends GerritMessageProvider {


    transient private final Map<ObjectId, String> buildIdMap = new ConcurrentHashMap<ObjectId, String>();
    @Override
    public Collection<CommentedFile> getFileComments(AbstractBuild build) {

        Collection<CommentedFile> comments = new ArrayList<CommentedFile>();

        if (Hudson.getInstance().getPlugin("analysis-collector") != null) {

            Set<FileAnnotation> result = getFileAnnotations(build);

            List<ObjectId> parents = getParents(build);

            Set<FileAnnotation> existingWarnigs = new HashSet<FileAnnotation>();

            boolean foundPreviousAction = false;
            for(ObjectId parent : parents) {

                AbstractBuild parentBuild = findBuildForParent(build, parent);

                AbstractResultAction<? extends BuildResult> action = getAction(parentBuild);

                if(action != null) {
                    foundPreviousAction = true;
                    Collection<FileAnnotation> fileAnnotations = getFileAnnotations(parentBuild);
                    existingWarnigs.addAll(fileAnnotations);
                }
            }
            if(foundPreviousAction) {
                //If there weren't previous result, do not send warnings. It might fill the review
                // with possibly thousands of warnings.
                Set<FileAnnotation> newAnnotations =
                        AnnotationDifferencer.getNewAnnotations(result, existingWarnigs);
                Map<String, List<LineComment>> fileComments = getComments(build, newAnnotations);

                for(Map.Entry<String, List<LineComment>> entry : fileComments.entrySet()) {
                    comments.add(new CommentedFile(entry.getKey(), entry.getValue()));
                }
            }
        }

        return comments;
    }

    private AbstractResultAction<? extends BuildResult> getAction(AbstractBuild build) {
        AbstractResultAction<? extends BuildResult> action = null;
        if(build != null) {
            action = build.getAction(AnalysisResultAction.class);
        }
        return action;
    }

    private AbstractBuild findBuildForParent(AbstractBuild build, ObjectId parent) {
        if(!buildIdMap.containsKey(parent)) {
            String parentBuildId = findParent(parent, build);
            buildIdMap.put(parent, parentBuildId);
        }
        String id = buildIdMap.get(parent);
        AbstractBuild parentBuild = null;
        if(id != null) {
            parentBuild = build.getProject().getBuild(id);
        }
        return parentBuild;
    }

    private String findParent(ObjectId parent, AbstractBuild build) {
        AbstractBuild previousBuild = build.getPreviousBuild();
        while (previousBuild != null) {
            BuildData buildData = previousBuild.getAction(BuildData.class);
            if(buildData != null) {
                if(buildData.lastBuild.getSHA1().equals(parent)) {
                    return previousBuild.getId();
                }
            }
            previousBuild = previousBuild.getPreviousBuild();
        }
        return null;
    }

    private List<ObjectId> getParents(final AbstractBuild build) {

        try {
            final EnvVars environment = build.getEnvironment(TaskListener.NULL);

            FilePath.FileCallable<List<ObjectId>> getParents = new ParentResolver(environment);

            return build.getWorkspace().act(getParents);
        } catch (IOException e) {
            return Collections.emptyList();
        } catch (InterruptedException e) {
            return Collections.emptyList();
        }
    }

    private Map<String, List<LineComment>> getComments(AbstractBuild build, final Collection<FileAnnotation> newWarnings) {
        Map<String, List<LineComment>> fileComments = new HashMap<String, List<LineComment>>();

        for(FileAnnotation annotation : newWarnings)
        {
            String filePath = annotation.getFileName();

            String message = annotation.getMessage();
            int line = annotation.getPrimaryLineNumber();
            if(!fileComments.containsKey(filePath)) {
                fileComments.put(filePath, new ArrayList<LineComment>());
            }
            fileComments.get(filePath).add(new LineComment(line, message));

        }
        return fileComments;
    }

    private Set<FileAnnotation> getFileAnnotations(final AbstractBuild build) {
        Set<FileAnnotation> resultAnnotations = Collections.emptySet();
        AbstractResultAction<? extends BuildResult> action = getAction(build);
        if (action != null) {
            resultAnnotations = action.getResult().getAnnotations();
        }
        resultAnnotations = Sets.newHashSet(Collections2.transform(resultAnnotations, new Function<FileAnnotation, FileAnnotation>() {
            public FileAnnotation apply(@Nullable FileAnnotation fileAnnotation) {
                fileAnnotation.setFileName(normalizeFilePath(build, fileAnnotation));
                return fileAnnotation;
            }
        }));
        return resultAnnotations;
    }

    private String normalizeFilePath(AbstractBuild build, FileAnnotation annotation) {
        String fileName = annotation.getFileName();
        String workspaceName;
        try {
            workspaceName = build.getWorkspace().absolutize().toString();
        } catch (IOException e) {
            workspaceName = build.getWorkspace().getName();
        } catch (InterruptedException e) {
            workspaceName = build.getWorkspace().getName();
        }

        //take path including workspace name.
        int index = fileName.indexOf(workspaceName);
        if(index >= 0) {
            fileName = fileName.substring(index);
            fileName = fileName.substring(workspaceName.length() + 1);
        }
        //remove workspace name
        return fileName;
    }

    private static class ParentResolver implements FilePath.FileCallable<List<ObjectId>> {

        private final EnvVars environment;

        public ParentResolver(EnvVars environment) {
            this.environment = environment;
        }

        public List<ObjectId> invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            GitClient client =
                    Git.with(TaskListener.NULL, environment).
                            in(f).getClient();
            Repository repository = client.getRepository();
            org.eclipse.jgit.api.Git git = new org.eclipse.jgit.api.Git(repository);
            Iterable<RevCommit> revCommits = null;
            try {
                revCommits = git.log().call();
            } catch (GitAPIException e) {
                e.printStackTrace();
            }
            RevCommit current = revCommits.iterator().next();
            RevCommit[] parents = current.getParents();
            List<ObjectId> parentIds = Lists.newArrayList();
            for (RevCommit parent : parents) {
                parentIds.add(parent.getId());
            }
            return parentIds;
        }
    }
}
