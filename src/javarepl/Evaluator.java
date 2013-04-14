package javarepl;

import com.googlecode.totallylazy.*;
import com.googlecode.totallylazy.annotations.multimethod;
import javarepl.expressions.*;
import javarepl.expressions.Value;

import javax.tools.JavaCompiler;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.List;

import static com.googlecode.totallylazy.Callables.toString;
import static com.googlecode.totallylazy.Either.left;
import static com.googlecode.totallylazy.Either.right;
import static com.googlecode.totallylazy.Files.*;
import static com.googlecode.totallylazy.Option.none;
import static com.googlecode.totallylazy.Option.some;
import static com.googlecode.totallylazy.Sequences.sequence;
import static java.io.File.pathSeparator;
import static javarepl.Evaluation.evaluation;
import static javarepl.EvaluationClassLoader.evaluationClassLoader;
import static javarepl.EvaluationContext.evaluationContext;
import static javarepl.Utils.randomIdentifier;
import static javarepl.expressions.Patterns.*;
import static javarepl.rendering.EvaluationClassRenderer.renderExpressionClass;
import static javax.tools.ToolProvider.getSystemJavaCompiler;

public class Evaluator {
    private JavaCompiler compiler = getSystemJavaCompiler();
    private EvaluationClassLoader classLoader;
    private EvaluationContext context;
    private File outputDirectory;

    public Evaluator() {
        initializeEvaluator(evaluationContext());
    }

    private Evaluator(EvaluationContext context) {
        initializeEvaluator(context);
    }

    public Either<? extends Throwable, Evaluation> evaluate(String expr) {
        Expression expression = createExpression(expr);
        Either<? extends Throwable, Evaluation> result = evaluate(expression);
        if (result.isLeft() && result.left() instanceof ExpressionCompilationException && expression instanceof Value) {
            result = evaluate(new Statement(expr));
        }

        return result;
    }

    public Option<Evaluation> lastEvaluation() {
        return context.lastEvaluation();
    }

    public List<Result> results() {
        return context.results().toList();
    }

    public Option<Result> result(String name) {
        return context.result(name);
    }

    public <T extends Expression> Sequence<T> expressionsOfType(Class<T> type) {
        return context.expressionsOfType(type);
    }

    public Sequence<Evaluation> evaluations() {
        return context.evaluations();
    }

    public void reset() {
        clearOutputDirectory();
        initializeEvaluator(evaluationContext());
    }

    private void initializeEvaluator(EvaluationContext evaluationContext) {
        context = evaluationContext;
        outputDirectory = randomOutputDirectory();
        classLoader = evaluationClassLoader(outputDirectory);
    }

    public final Option<Class> typeOfExpression(String expression) {
        Evaluator localEvaluator = new Evaluator(context);
        Either<? extends Throwable, Evaluation> evaluation = localEvaluator.evaluate(expression);

        Option<Class> expressionType = none();
        if (evaluation.isRight()) {
            Option<Result> result = evaluation.right().result();
            if (!result.isEmpty()) {
                expressionType = some((Class) result.get().value().getClass());
            }
        }

        localEvaluator.clearOutputDirectory();
        return expressionType;
    }

    public void clearOutputDirectory() {
        deleteFiles(outputDirectory);
        delete(outputDirectory);
    }

    public void addClasspathUrl(URL classpathUrl) {
        classLoader.addURL(classpathUrl);
    }

    private Expression createExpression(String expr) {
        if (isValidImport(expr))
            return new Import(expr);

        if (isValidType(expr))
            return new Type(expr);

        if (isValidMethod(expr))
            return new Method(expr);

        if (isValidAssignmentWithType(expr))
            return new AssignmentWithType(expr);

        if (isValidAssignment(expr))
            return new Assignment(expr);


        return new Value(expr);
    }

    @multimethod
    private Either<? extends Throwable, Evaluation> evaluate(Expression expression) {
        return new multi() {
        }.<Either<? extends Throwable, Evaluation>>
                methodOption(expression).getOrElse(evaluateExpression(expression));
    }

    @multimethod
    private Either<? extends Throwable, Evaluation> evaluate(Type expression) {
        if (classLoader.isClassLoaded(expression.canonicalName())) {
            return left(new UnsupportedOperationException("Redefining classes not supported"));
        }

        try {
            File outputPath = directory(outputDirectory, expression.typePackage().getOrElse("").replace('.', File.separatorChar));
            File outputJavaFile = file(outputPath, expression.type() + ".java");

            String sources = renderExpressionClass(context, expression.type(), expression);
            Files.write(sources.getBytes(), outputJavaFile);
            compile(outputJavaFile);

            classLoader.loadClass(expression.canonicalName());

            Evaluation evaluation = evaluation(expression.type(), sources, expression, Result.noResult());
            context = context.addEvaluation(evaluation);

            return right(evaluation);
        } catch (Exception e) {
            return left(Utils.unwrapException(e));
        }
    }

    private Either<? extends Throwable, Evaluation> evaluateExpression(Expression expression) {
        String className = randomIdentifier("Evaluation");

        try {
            File outputJavaFile = file(outputDirectory, className + ".java");
            String sources = renderExpressionClass(context, className, expression);
            Files.write(sources.getBytes(), outputJavaFile);

            compile(outputJavaFile);

            Class<?> expressionClass = classLoader.loadClass(className);
            Constructor<?> constructor = expressionClass.getDeclaredConstructor(EvaluationContext.class);
            Object expressionInstance = constructor.newInstance(context);

            Object resultObject = expressionClass.getMethod("evaluate").invoke(expressionInstance);

            if (resultObject != null) {
                Evaluation evaluation = evaluation(className, sources, expression, some(Result.result(nextResultKeyFor(expression), resultObject)));
                context = context.addEvaluation(evaluation);
                return right(evaluation);
            } else {
                Evaluation evaluation = evaluation(className, sources, expression, Result.noResult());

                context = context.addEvaluation(evaluation);
                return right(evaluation);
            }
        } catch (Throwable e) {
            return left(Utils.unwrapException(e));
        }
    }

    private String nextResultKeyFor(Expression expression) {
        return (expression instanceof WithKey)
                ? ((WithKey) expression).key()
                : context.nextResultKey();
    }

    private void compile(File file) throws Exception {
        OutputStream errorStream = new ByteArrayOutputStream();
        String classpath = sequence(System.getProperty("java.class.path"))
                .join(sequence(classLoader.getURLs()).map(toString)).toString(pathSeparator);

        int errorCode = compiler.run(null, null, errorStream, "-cp", classpath, file.getCanonicalPath());

        if (errorCode != 0)
            throw new ExpressionCompilationException(errorCode, errorStream.toString());
    }

    private static File randomOutputDirectory() {
        File file = temporaryDirectory("JavaREPL/" + randomFilename());
        file.deleteOnExit();
        return file;
    }
}
