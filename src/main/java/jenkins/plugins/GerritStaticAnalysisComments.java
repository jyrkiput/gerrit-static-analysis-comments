package jenkins.plugins;

import com.sonyericsson.hudson.plugins.gerrit.trigger.gerritnotifier.GerritMessageProvider;
import com.sonyericsson.hudson.plugins.gerrit.trigger.gerritnotifier.rest.object.CommentedFile;
import com.sonyericsson.hudson.plugins.gerrit.trigger.gerritnotifier.rest.object.LineComment;
import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.Hudson;
import hudson.plugins.analysis.collector.AnalysisResult;
import hudson.plugins.analysis.collector.AnalysisResultAction;
import hudson.plugins.analysis.core.AbstractResultAction;
import hudson.plugins.analysis.core.BuildResult;
import hudson.plugins.analysis.core.ParserResult;
import hudson.plugins.analysis.util.model.FileAnnotation;

import java.io.IOException;
import java.util.*;

@Extension
public class GerritStaticAnalysisComments extends GerritMessageProvider {

    @Override
    public Collection<CommentedFile> getFileComments(AbstractBuild build) {
        Collection<CommentedFile> comments = new ArrayList<CommentedFile>();
        if (Hudson.getInstance().getPlugin("analysis-collector") != null) {

            AnalysisResult result = getAnalysisResult(build);

            Map<String, List<LineComment>> fileComments = getComments(build, result);

            for(Map.Entry<String, List<LineComment>> entry : fileComments.entrySet()) {
                comments.add(new CommentedFile(entry.getKey(), entry.getValue()));
            }
        }

        return comments;
    }

    private Map<String, List<LineComment>> getComments(AbstractBuild build, AnalysisResult result) {
        Map<String, List<LineComment>> fileComments = new HashMap<String, List<LineComment>>();

        for(FileAnnotation annotation : result.getNewWarnings())
        {
            String filePath = getFilePath(build, annotation);

            String message = annotation.getMessage();
            int line = annotation.getPrimaryLineNumber();
            if(!fileComments.containsKey(filePath)) {
                fileComments.put(filePath, new ArrayList<LineComment>());
            }
            fileComments.get(filePath).add(new LineComment(line, message));

        }
        return fileComments;
    }

    private AnalysisResult getAnalysisResult(AbstractBuild build) {
        ParserResult overallResult = new ParserResult(build.getWorkspace());
        String defaultEncoding = "utf-8";
        AbstractResultAction<? extends BuildResult> action = build.getAction(AnalysisResultAction.class);

        if (action != null) {
            Collection<FileAnnotation> resultAnnotations = action.getResult().getNewWarnings();
            overallResult.addAnnotations(resultAnnotations);
        }
        return new AnalysisResult(build, defaultEncoding, overallResult, false);
    }

    private String getFilePath(AbstractBuild build, FileAnnotation annotation) {
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
        fileName = fileName.substring(fileName.indexOf(workspaceName));
        //remove workspace name
        fileName = fileName.substring(workspaceName.length() + 1);
        return fileName;
    }
}
