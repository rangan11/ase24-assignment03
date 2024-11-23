import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
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

        String seedInput = "<html a=\"value\">...</html>";

        ProcessBuilder builder = getProcessBuilderForCommand(commandToFuzz, workingDirectory);
        System.out.printf("Command: %s\n", builder.command());

        List<Function<String, String>> mutators = List.of(
                Fuzzer::duplicateTags,
                input -> increaseAttributeLength(input, 20),
                input -> increaseContentLength(input, 100),
                Fuzzer::extendOpeningTagName
        );
		
		boolean allZeroExitCodes = runCommand(builder, seedInput, getMutatedInputs(seedInput, mutators));
        System.exit(allZeroExitCodes ? 0 : 1);
		
    }
	
	public static String duplicateTags(String input) {
        return input.replaceAll("<", "<<").replaceAll(">", ">>");
    }

    public static String increaseAttributeLength(String input, int targetLength) {
        Pattern pattern = Pattern.compile("(\\w+=\"[^\"]*)\"");
        Matcher matcher = pattern.matcher(input);
        StringBuilder result = new StringBuilder();
        boolean found = false;

        while (matcher.find()) {
            found = true;
            StringBuilder value = new StringBuilder(matcher.group(1));
            while (value.length() < targetLength) {
                value.append("X");
            }
            matcher.appendReplacement(result, value + "\"");
        }
        matcher.appendTail(result);
        return found ? result.toString() : input;
    }

    public static String increaseContentLength(String input, int targetLength) {
        Pattern pattern = Pattern.compile(">([^<]*)<");
        Matcher matcher = pattern.matcher(input);
        StringBuilder result = new StringBuilder();
        boolean found = false;

        while (matcher.find()) {
            found = true;
            StringBuilder content = new StringBuilder(matcher.group(1));
            while (content.length() < targetLength) {
                content.append("Y");
            }
            matcher.appendReplacement(result, ">" + content + "<");
        }
        matcher.appendTail(result);
        return found ? result.toString() : input;
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
        return Stream.concat(Stream.of(seedInput), mutatedInputs.stream())
                .map(input -> {
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

                        return exitCode;
                    } catch (Exception e) {
                        System.err.printf("Error while testing input: %s\n", e.getMessage());
                        return -1;
                    }
                })
                .allMatch(exitCode -> exitCode == 0); // Ensure all exit codes are 0
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
