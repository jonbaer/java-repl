package javarepl.console;

import com.googlecode.totallylazy.Functions;
import com.googlecode.totallylazy.Mapper;
import com.googlecode.totallylazy.Rule;
import com.googlecode.totallylazy.Rules;
import com.googlecode.yadic.Container;
import com.googlecode.yadic.SimpleContainer;
import javarepl.Evaluator;
import javarepl.completion.*;
import javarepl.console.commands.Command;
import javarepl.console.commands.Commands;
import javarepl.expressions.Expression;
import javarepl.rendering.ExpressionTemplate;

import static com.googlecode.totallylazy.Predicates.always;
import static com.googlecode.totallylazy.Predicates.notNullValue;
import static com.googlecode.totallylazy.Strings.blank;
import static com.googlecode.totallylazy.Strings.startsWith;
import static javarepl.Utils.randomIdentifier;
import static javarepl.completion.Completers.javaKeywordCompleter;
import static javarepl.completion.TypeResolver.functions.defaultPackageResolver;
import static javarepl.console.ConsoleHistory.historyFromFile;
import static javarepl.console.ConsoleResult.emptyResult;
import static javarepl.console.ConsoleStatus.*;
import static javarepl.console.commands.Command.functions.completer;
import static javarepl.rendering.EvaluationClassRenderer.renderExpressionClass;
import static javarepl.rendering.ExpressionTokenRenderer.EXPRESSION_TOKEN;

public final class SimpleConsole implements Console {
    private final Container context;
    private ConsoleStatus status = Idle;

    public SimpleConsole(ConsoleConfig config) {
        registerShutdownHook();

        context = new SimpleContainer();
        context.addInstance(Container.class, context);
        context.addInstance(Console.class, this);
        context.addInstance(ConsoleHistory.class, historyFromFile(startsWith(":h!").or(blank()), config.historyFile));
        context.addInstance(ConsoleConfig.class, config);
        context.addInstance(Evaluator.class, config.evaluator);
        context.addInstance(ConsoleLogger.class, config.logger);
        context.addInstance(TypeResolver.class, new TypeResolver(defaultPackageResolver()));
        context.add(Commands.class);
        context.addInstance(Completer.class, new AggregateCompleter(context.get(Commands.class).allCommands().map(completer()).filter(notNullValue())
                .append(javaKeywordCompleter())
                .append(new ConsoleCompleter(context.get(Evaluator.class), context.get(TypeResolver.class)))
                .append(new TypeCompleter(context.get(TypeResolver.class)))
                .append(new StaticMemberCompleter(context.get(Evaluator.class)))
                .append(new InstanceMemberCompleter(context.get(Evaluator.class)))

        ));

        context.get(Evaluator.class).addResults(config.results);
    }

    public ConsoleResult execute(String expression) {
        context.get(ConsoleHistory.class).add(expression);
        return executeExpression(expression);
    }

    public CompletionResult completion(String expression) {
        return context.get(Completer.class).apply(expression);
    }

    public ExpressionTemplate template(String expression) {
        Evaluator evaluator = context.get(Evaluator.class);
        Expression parsedExpression = evaluator.parseExpression(expression).right();

        return new ExpressionTemplate(renderExpressionClass(evaluator.context(), randomIdentifier("Evaluation"), parsedExpression), EXPRESSION_TOKEN);
    }

    private ConsoleResult executeExpression(String expression) {
        return evaluationRules().apply(expression);
    }

    public Container context() {
        return context;
    }

    public ConsoleStatus status() {
        return status;
    }

    public ConsoleHistory history() {
        return context.get(ConsoleHistory.class);
    }

    public void start() {
        if (status() == Idle) {
            status = Starting;

            for (String expression : context.get(ConsoleConfig.class).expressions) {
                executeExpression(expression);
            }

            status = Running;
        }
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                shutdown();
            }
        });
    }

    public void shutdown() {
        status = Terminating;
        context.get(ConsoleHistory.class).save();
        context.get(Evaluator.class).clearOutputDirectory();
        status = Terminated;
    }

    private Rules<String, ConsoleResult> evaluationRules() {
        Rules<String, ConsoleResult> rules = Rules.rules();
        for (Command command : context.get(Commands.class).allCommands()) {
            rules.addLast(Rule.rule(command.predicate(), asFunction(command)));
        }
        return rules.addLast(Rule.rule(always(), Functions.<String, ConsoleResult>returns1(emptyResult())));
    }

    private Mapper<String, ConsoleResult> asFunction(final Command command) {
        return new Mapper<String, ConsoleResult>() {
            public ConsoleResult call(String expression) throws Exception {
                context.get(ConsoleLogger.class).reset();
                command.execute(expression);
                ConsoleResult result = new ConsoleResult(expression, context.get(ConsoleLogger.class).logs());
                return result;
            }
        };
    }
}
