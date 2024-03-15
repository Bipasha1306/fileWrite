import graphql.language.*;
import graphql.parser.Parser;
import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class ReadYamlFile {

    public static void main(String[] args) {
        // Provide the path to your YAML file
        String yamlFilePath = "application.yml";

        // Read YAML file and print its content
        try (InputStream input = Files.newInputStream(Paths.get(yamlFilePath))) {
            Yaml yaml = new Yaml();
            Map<String, Object> yamlData = yaml.load(input);
            // query
            String q1 = (String) yamlData.get("graphqlQuery");
            // response
            String r1 = (String) yamlData.get("graphqlResponse");

            List<String> headers = extractKeysFromQuery(q1);
            System.out.println(headers);
            storeDataInTXT(r1, headers);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Extracts keys from a GraphQL query.
     *
     * @param query The GraphQL query string.
     * @return List of keys extracted from the query.
     */
    public static List<String> extractKeysFromQuery(String query) {
        List<String> keys = new ArrayList<>();
        Document document = new Parser().parseDocument(query);

        document.getDefinitions().stream()
                .filter(definition -> definition instanceof OperationDefinition)
                .map(definition -> (OperationDefinition) definition)
                .forEach(operationDefinition -> operationDefinition.getSelectionSet().getSelections().stream()
                        .filter(selection -> selection instanceof Field)
                        .map(selection -> (Field) selection)
                        .forEach(field -> extractKeys(field, "", keys)));

        return keys;
    }

    /**
     * Recursively extracts keys from a GraphQL field.
     *
     * @param field  The GraphQL field.
     * @param prefix The prefix of the nested keys.
     * @param keys   The list to store the extracted keys.
     */
    private static void extractKeys(Field field, String prefix, List<String> keys) {
        if (field.getSelectionSet() != null) {
            field.getSelectionSet().getSelections().stream()
                    .filter(selection -> selection instanceof Field)
                    .map(selection -> (Field) selection)
                    .forEach(nestedField -> extractKeys(nestedField, prefix + (field.getName().equals("accountByIds") ? "" : field.getName() + "."), keys));
        } else {
            keys.add(prefix + field.getName());
        }
    }

    /**
     * Stores data from JSON response into a text file.
     *
     * @param jsonResponse The JSON response string.
     * @param keys         The list of keys to extract data from JSON.
     */
    public static void storeDataInTXT(String jsonResponse, List<String> keys) {
        try {
            // Parse JSON data
            JSONObject jsonObject = new JSONObject(jsonResponse);
            JSONArray accountByIds = jsonObject.getJSONObject("data").getJSONArray("accountByIds");

            // Write data to text file
            BufferedWriter writer = new BufferedWriter(new FileWriter("output.txt"));
            writer.write(String.join("\t", keys));
            writer.newLine();

            for (int i = 0; i < accountByIds.length(); i++) {
                JSONObject account = accountByIds.getJSONObject(i);
                String[] values = keys.stream()
                        .map(key -> {
                            String[] nestedKeys = key.split("\\.");
                            Object value = getValue(account, nestedKeys);
                            return (value instanceof String) ? "\"" + value + "\"" : (value != null ? value.toString() : "null");
                        })
                        .toArray(String[]::new);
                // Print values
                System.out.println(String.join("\t", values));
                writer.write(String.join("\t", values));
                writer.newLine();
            }
            writer.close();
            System.out.println("Data has been written to output.txt");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Recursively gets nested values from a JSON object.
     *
     * @param jsonObject The JSON object.
     * @param keys       The array of keys representing the nested structure.
     * @return The value obtained from the JSON object.
     */
    private static Object getValue(JSONObject jsonObject, String[] keys) {
        JSONObject currentObject = jsonObject;
        for (String key : keys) {
            if (!currentObject.has(key)) {
                return null;
            }
            Object value = currentObject.get(key);
            if (value instanceof JSONObject) {
                currentObject = (JSONObject) value;
            } else if (value instanceof JSONArray) {
                // Handle case where key represents an empty array
                JSONArray jsonArray = (JSONArray) value;
                if (jsonArray.isEmpty()) {
                    return null;
                } else {
                    // Assuming there's only one object in the array
                    currentObject = jsonArray.getJSONObject(0);
                }
            } else {
                return value;
            }
        }
        return currentObject;
    }
}
