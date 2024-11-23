import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Fuzzer {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java Fuzzer.java \"<command_to_fuzz>\"");
            System.exit(1);
        }
        String commandToFuzz = args[0];
        String workingDirectory = "./";

        if (!Files.exists(Paths.get(workingDirectory, commandToFuzz))) {
            throw new RuntimeException("Could not find command '%s'.".formatted(commandToFuzz));
        }

        String seedInput = "<html></html>";

        ProcessBuilder builder = getProcessBuilderForCommand(commandToFuzz, workingDirectory);
        System.out.printf("Command: %s\n", builder.command());

        List<Function<String, String>> mutators = List.of(
                Fuzzer::duplicateTags,
                input -> increaseAttributeLength(input, 20),
                input -> increaseContentLength(input, 100),
                Fuzzer::extendOpeningTagName
        );

        List<String> mutatedInputs = getMutatedInputs(seedInput, mutators);

        boolean allZeroExitCodes = runCommand(builder, seedInput, mutatedInputs);
        System.exit(allZeroExitCodes ? 0 : 1);


    }

    public static String duplicateTags(String input) {
        return input.replaceAll("<", "<<").replaceAll(">", ">>");
    }

    public static String increaseAttributeLength(String input, int targetLength) {
        Pattern pattern = Pattern.compile("(\\w+)=\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(input);
        StringBuilder result = new StringBuilder();
        boolean found = false;

        while (matcher.find()) {
            found = true;
            String attributeName = matcher.group(1);
            StringBuilder value = new StringBuilder(matcher.group(2));
            while (value.length() < targetLength) {
                value.append("X");
            }
            matcher.appendReplacement(result, attributeName + "=\"" + value + "\"");
        }
        matcher.appendTail(result);

        if (!found && input.matches(".*?<\\w+.*?>.*")) {
            Pattern tagPattern = Pattern.compile("<(\\w+)([^>]*)>");
            Matcher tagMatcher = tagPattern.matcher(input);
            if (tagMatcher.find()) {
                result = new StringBuilder(input);
                int insertPosition = tagMatcher.end(1);
                result.insert(insertPosition, " newAttr=\"XXXXXXXXXX\"");
            }
        }

        return result.toString();
    }

    public static String increaseContentLength(String input, int targetLength) {
        Pattern pattern = Pattern.compile("<(\\w+)[^>]*>(.*?)</(\\1)>"); // Capture tag content
        Matcher matcher = pattern.matcher(input);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String content = matcher.group(2);
            StringBuilder newContent = new StringBuilder(content);
            while (newContent.length() < targetLength) {
                newContent.append("Y");
            }
            matcher.appendReplacement(result, "<" + matcher.group(1) + ">" + newContent + "</" + matcher.group(1) + ">");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String extendOpeningTagName(String input) {
        Pattern pattern = Pattern.compile("<(\\w+)");
        Matcher matcher = pattern.matcher(input);
        StringBuilder result = new StringBuilder();
        boolean found = false;

        while (matcher.find()) {
            found = true;
            StringBuilder longTagName = new StringBuilder(matcher.group(1));
            while (longTagName.length() <= 16) {
                longTagName.append("X");
            }
            matcher.appendReplacement(result, "<" + longTagName);
        }
        matcher.appendTail(result);
        return found ? result.toString() : input;
    }

    private static ProcessBuilder getProcessBuilderForCommand(String command, String workingDirectory) {
        ProcessBuilder builder = new ProcessBuilder();
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        if (isWindows) {
            builder.command("cmd.exe", "/c", command);
        } else {
            builder.command("sh", "-c", command);
        }
        builder.directory(new File(workingDirectory));
        builder.redirectErrorStream(true); // redirect stderr to stdout
        return builder;
    }

    private static boolean runCommand(ProcessBuilder builder, String seedInput, List<String> mutatedInputs) {
        boolean allSuccess = true;

        for (String input : Stream.concat(Stream.of(seedInput), mutatedInputs.stream()).toList()) {
            try {
                System.out.printf("Testing input: %s\n", input);

                Process process = builder.start();

                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
                    writer.write(input);
                    writer.flush();

                }

                String output = readStreamIntoString(process.getInputStream());
                int exitCode = process.waitFor();

                System.out.printf("Output: %s\n", output.trim());
                System.out.printf("Exit Code: %d\n\n", exitCode);

                if (exitCode != 0) {
                    allSuccess = false;
                    System.err.println("Error: Program exited with a non-zero exit code.");
                }
            } catch (Exception e) {
                System.err.printf("Error while testing input: %s\n", e.getMessage());
                allSuccess = false;
            }
        }

        return allSuccess;
    }

    private static String readStreamIntoString(InputStream inputStream) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        return reader.lines()
                .map(line -> line + System.lineSeparator())
                .collect(Collectors.joining());
    }

    private static List<String> getMutatedInputs(String seedInput, Collection<Function<String, String>> mutators) {
        return mutators.stream()
                .map(mutator -> mutator.apply(seedInput))
                .collect(Collectors.toList());
    }
}
