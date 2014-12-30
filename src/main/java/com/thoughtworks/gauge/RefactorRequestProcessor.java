package com.thoughtworks.gauge;


import main.Messages;
import main.Spec;
import org.walkmod.javalang.JavaParser;
import org.walkmod.javalang.ast.CompilationUnit;
import org.walkmod.javalang.ast.body.MethodDeclaration;
import org.walkmod.javalang.ast.body.Parameter;
import org.walkmod.javalang.ast.body.VariableDeclaratorId;
import org.walkmod.javalang.ast.expr.AnnotationExpr;
import org.walkmod.javalang.ast.expr.BinaryExpr;
import org.walkmod.javalang.ast.expr.SingleMemberAnnotationExpr;
import org.walkmod.javalang.ast.expr.StringLiteralExpr;
import org.walkmod.javalang.ast.type.ClassOrInterfaceType;
import org.walkmod.javalang.visitors.VoidVisitorAdapter;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RefactorRequestProcessor implements IMessageProcessor {
    @Override
    public Messages.Message process(Messages.Message message) {
        Messages.RefactorRequest refactorRequest = message.getRefactorRequest();
        JavaParser.setCacheParser(true);
        Element element = findJavaFile(refactorRequest.getOldStepValue().getParameterizedStepValue(), refactorRequest.getNewStepValue(), refactorRequest.getParamPositionsList());
        if (element == null){
           return getMessage(message, false, "Step Implementation Not Found");
        }
        try {
            new RefactorFile(element).refactor();
        } catch (IOException e) {
            return getMessage(message,false,"Unable to read/write file while refactoring");
        }
        return getMessage(message,true,"");
    }

    private Messages.Message getMessage(Messages.Message message, boolean success, String errorMessage) {
        if (success)
            return Messages.Message.newBuilder()
                    .setMessageId(message.getMessageId())
                    .setMessageType(Messages.Message.MessageType.RefactorResponse)
                    .setRefactorResponse(Messages.RefactorResponse.newBuilder().setSuccess(true).build())
                    .build();
        else
            return Messages.Message.newBuilder()
                    .setMessageId(message.getMessageId())
                    .setMessageType(Messages.Message.MessageType.RefactorResponse)
                    .setRefactorResponse(Messages.RefactorResponse.newBuilder().setSuccess(false).setError(errorMessage).build())
                    .build();
    }

    private Element findJavaFile(String oldStepValue, Spec.ProtoStepValue newStepValue, List<Messages.ParameterPosition> paramPositions) {
        File workingDir = new File(System.getProperty("user.dir"));
        List<JavaParseWorker> javaFiles = parseAllJavaFiles(workingDir);
        for (JavaParseWorker javaFile : javaFiles) {
            CompilationUnit compilationUnit = javaFile.getCompilationUnit();
            MethodVisitor methodVisitor = new MethodVisitor(oldStepValue, newStepValue, paramPositions);
            methodVisitor.visit(compilationUnit, null);
            if (methodVisitor.refactored) {
                methodVisitor.element.file = javaFile.getJavaFile();
                return methodVisitor.element;
            }
        }
        return null;
    }

    private List<JavaParseWorker> parseAllJavaFiles(File workingDir) {
        ArrayList<JavaParseWorker> javaFiles = new ArrayList<JavaParseWorker>();
        File[] allFiles = workingDir.listFiles();
        for (File file : allFiles) {
            if (file.isDirectory()) {
                javaFiles.addAll(parseAllJavaFiles(file));
            } else {
                if (file.getName().toLowerCase().endsWith(".java")) {
                    JavaParseWorker worker = new JavaParseWorker(file);
                    worker.start();
                    javaFiles.add(worker);
                }
            }
        }

        return javaFiles;
    }

    class JavaParseWorker extends Thread {

        private File javaFile;
        private CompilationUnit compilationUnit;

        JavaParseWorker(File javaFile) {
            this.javaFile = javaFile;
        }

        @Override
        public void run() {
            try {
                FileInputStream in = new FileInputStream(javaFile);
                compilationUnit = JavaParser.parse(in);
                in.close();
            } catch (Exception e) {
                // ignore exceptions
            }
        }

        public File getJavaFile() {
            return javaFile;
        }

        CompilationUnit getCompilationUnit() {
            try {
                join();
            } catch (InterruptedException e) {

            }
            return compilationUnit;
        }
    }

    public class Element {
        public int beginLine;
        public int endLine;
        public int beginColumn;
        public int endColumn;
        public String text;
        public File file;

        public Element(int beginLine, int endLine, int beginColumn, int endColumn, String text, File file) {
            this.beginLine = beginLine;
            this.endLine = endLine;
            this.beginColumn = beginColumn;
            this.endColumn = endColumn;
            this.text = text;
            this.file = file;
        }
    }

    private class MethodVisitor extends VoidVisitorAdapter {
        private String oldStepValue;
        private Spec.ProtoStepValueOrBuilder newStepValue;
        private List<Messages.ParameterPosition> paramPositions;
        private boolean refactored;
        public Element element;

        public MethodVisitor(String oldStepValue, Spec.ProtoStepValueOrBuilder newStepValue, List<Messages.ParameterPosition> paramPositions) {
            this.oldStepValue = oldStepValue;
            this.newStepValue = newStepValue;
            this.paramPositions = paramPositions;
        }

        @Override
        public void visit(MethodDeclaration methodDeclaration, Object arg) {
            for (AnnotationExpr annotationExpr : methodDeclaration.getAnnotations()) {
                if (!(annotationExpr instanceof SingleMemberAnnotationExpr))
                    continue;

                SingleMemberAnnotationExpr annotation = (SingleMemberAnnotationExpr) annotationExpr;
                if (annotation.getMemberValue() instanceof BinaryExpr) {
                    ScriptEngine engine = new ScriptEngineManager().getEngineByName("JavaScript");
                    try {
                        Object result = engine.eval(annotation.getMemberValue().toString());
                        refactor(methodDeclaration,new StringLiteralExpr(result.toString()),annotation);
                    } catch (ScriptException e) {
                        continue;
                    }
                }
                if (annotation.getMemberValue() instanceof StringLiteralExpr) {
                    StringLiteralExpr memberValue = (StringLiteralExpr) annotation.getMemberValue();
                    refactor(methodDeclaration, memberValue, annotation);
                }
            }
        }

        private void refactor(MethodDeclaration methodDeclaration, StringLiteralExpr memberValue, SingleMemberAnnotationExpr annotation) {
            if (memberValue.getValue().equals(oldStepValue)) {
                List<Parameter> newParameters = Arrays.asList(new Parameter[paramPositions.size()]);
                memberValue.setValue(newStepValue.getParameterizedStepValue());
                List<Parameter> parameters = methodDeclaration.getParameters();
                for (int i = 0, paramPositionsSize = paramPositions.size(); i < paramPositionsSize; i++) {
                    if (paramPositions.get(i).getOldPosition() < 0)
                        newParameters.set(i,new Parameter(new ClassOrInterfaceType("String"), new VariableDeclaratorId(newStepValue.getParameters(i))));
                    else
                        newParameters.set(paramPositions.get(i).getNewPosition(),parameters.get(paramPositions.get(i).getOldPosition()));
                }
                methodDeclaration.setParameters(newParameters);
                annotation.setMemberValue(memberValue);
                this.element = new Element(methodDeclaration.getBeginLine(),methodDeclaration.getEndLine(),methodDeclaration.getBeginColumn(),methodDeclaration.getEndColumn(),methodDeclaration.toString(), null);
                this.refactored = true;
            }
        }
    }
}
