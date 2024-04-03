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

    private static boolean containEdges = false;

    public static void main(String[] args) {
        // Provide the path to your YAML file
        String yamlFilePath = "application.yml";

        // Read YAML file and print its content
        try (InputStream input = Files.newInputStream(Paths.get(yamlFilePath))) {
            Yaml yaml = new Yaml();
            Map<String, Object> yamlData = yaml.load(input);
            // queryName
            String queryName = (String) yamlData.get("queryName");
            // query
            String q1 = (String) yamlData.get("q2");
            // response
            String r1 = (String) yamlData.get("r2");

            // Create a HashMap with String keys and Object values
            Map<String, Object> eventMap = new HashMap<>();

            // Put dummy values into the map
            eventMap.put("129583", "Insert");
            eventMap.put("23980", "Delete");

            List<String> headers = extractKeysFromQuery(q1, queryName);
            System.out.println(headers);
            StringWriter stringWriter = new StringWriter();
            storeDataInTXT(r1, headers, queryName,stringWriter,eventMap);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Extracts keys from a GraphQL query.
     *
     * @param query The GraphQL query string.
     * @param queryName The query name to parse.
     * @return List of keys extracted from the query.
     */
    public static List<String> extractKeysFromQuery(String query, String queryName) {
        List<String> keys = new ArrayList<>();
        Document document = new Parser().parseDocument(query);

        document.getDefinitions().stream()
                .filter(definition -> definition instanceof OperationDefinition)
                .map(definition -> (OperationDefinition) definition)
                .forEach(operationDefinition -> operationDefinition.getSelectionSet().getSelections().stream()
                        .filter(selection -> selection instanceof Field)
                        .map(selection -> (Field) selection)
                        .forEach(field -> extractKeys(field, queryName+".", queryName, keys)));

        return keys;
    }

    /**
     * Recursively extracts keys from a GraphQL field.
     *
     * @param field  The GraphQL field.
     * @param prefix The prefix of the nested keys.
     * @param queryName The query name to parse.
     * @param keys   The list to store the extracted keys.
     */
    private static void extractKeys(Field field, String prefix, String queryName, List<String> keys) {
        if(field.getName().equals("edges") || field.getName().equals("node")) containEdges = true;

        if (field.getSelectionSet() != null) {
            field.getSelectionSet().getSelections().stream()
                    .filter(selection -> selection instanceof Field)
                    .map(selection -> (Field) selection)
                    .forEach(nestedField -> extractKeys(nestedField, prefix + (field.getName().equals(queryName) ? "" : field.getName() + "."), queryName, keys));
        } else {
            keys.add(prefix + field.getName());
        }
    }

    /**
     * Stores data from JSON response into StringWriter.
     *
     * @param jsonResponse The JSON response string.
     * @param keys         The list of keys to extract data from JSON.
     * @param queryName    The query name to parse.
     * @param stringWriter The StringWriter to write the values and header.
     * @param eventTypeMap A map containing event types for account IDs.
     */
    public static void storeDataInTXT(String jsonResponse, List<String> keys, String queryName, StringWriter stringWriter, Map<String, Object> eventTypeMap) {
        try {
            // Parse JSON data
            JSONObject jsonObject = new JSONObject(jsonResponse);
            JSONArray jsonArray;
            if (containEdges) {
                jsonArray = jsonObject.getJSONObject("data").getJSONObject(queryName).getJSONArray("edges");
            } else {
                jsonArray = jsonObject.getJSONObject("data").getJSONArray(queryName);
            }

            // Create a new list for headers
            List<String> headers = new ArrayList<>(keys);
            headers.add("eventType");

            stringWriter.write(String.join("\t", headers));
            stringWriter.write("\n");

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject account = jsonArray.getJSONObject(i);
                String accountId;
                if (account.has("node")) {
                    accountId = String.valueOf(account.getJSONObject("node").getInt("accountId"));
                } else {
                    accountId = String.valueOf(account.getInt("accountId"));
                }
                String[] values = keys.stream()
                        .map(key -> {
                            String[] nestedKeys = key.split("\\.");
                            String[] newKeys = Arrays.copyOfRange(nestedKeys, containEdges ? 2 : 1, nestedKeys.length);
                            Object value = getValue(account, newKeys);
                            return (value != null) ? "\"" + value.toString() + "\"" : "\"\"";
                        })
                        .toArray(String[]::new);

                // Add event type value
                String eventTypeValue = (String) eventTypeMap.get(accountId);

                // If eventType is "Delete", only show accountId and eventType with ""
                if ("Delete".equals(eventTypeValue)) {
                    int keySize = keys.size();
                    String[] deleteValues = new String[keySize + 1];
                    deleteValues[0] = "\"" + accountId + "\"";
                    for (int k = 1; k < keySize; k++) {
                        deleteValues[k] = "\"\"";
                    }
                    deleteValues[keySize] = "\"Delete\"";
                    values = deleteValues;
                } else {
                    values = Arrays.copyOf(values, values.length + 1);
                    values[values.length - 1] = (eventTypeValue != null) ? "\"" + eventTypeValue + "\"" : "\"\"";
                }


                // Print values
                System.out.println(String.join("\t", values));
                stringWriter.write(String.join("\t", values));
                stringWriter.write("\n");
            }
            stringWriter.close();
            String result = stringWriter.toString();
            System.out.println("Data has been written to output.txt");
            System.out.println(result);
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