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
            String q1 = (String) yamlData.get("q3");
            // response
            String r1 = (String) yamlData.get("r3");

            // Create a HashMap with String keys and Object values
            Map<String, Object> eventMap = new HashMap<>();

            // Put dummy values into the map
            eventMap.put("129583", "Delete");
            eventMap.put("23980", "Update");
            eventMap.put("12345", "Delete");
            eventMap.put("23", "Delete");
            eventMap.put("98", "Delete");
            eventMap.put("66", "Delete");

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
     * Stores data from JSON response into a text file.
     *
     * @param jsonResponse The JSON response string.
     * @param keys         The list of keys to extract data from JSON.
     * @param queryName    The query name to parse.
     * @param stringWriter write the values and header
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

            // Iterate over the eventTypeMap to find account IDs marked for deletion
            for (String accountId : eventTypeMap.keySet()) {
                // Check if the eventType for this account is "Delete"
                if (eventTypeMap.get(accountId).equals("Delete")) {
                    // Flag to check if the account is found in the JSON response
                    boolean found = false;

                    // Iterate over the JSON response to find the account
                    for (int i = 0; i < jsonArray.length(); i++) {
                        JSONObject account = jsonArray.getJSONObject(i);
                        String currentAccountId;
                        if (account.has("node")) {
                            currentAccountId = String.valueOf(account.getJSONObject("node").getInt("accountId"));
                        } else {
                            currentAccountId = String.valueOf(account.getInt("accountId"));
                        }
                        // If the account ID is found, set the found flag to true and break the loop
                        if (currentAccountId.equals(accountId)) {
                            found = true;
                            break;
                        }
                    }
                    // If the account ID is not found, write a "Hard Delete" entry
                    if (!found) {
                        String[] hardDeleteValues = new String[keys.size() + 1];
                        Arrays.fill(hardDeleteValues, "\"\"");
                        hardDeleteValues[0] = "\"" + accountId + "\"";
                        hardDeleteValues[hardDeleteValues.length - 1] = "\"Hard Delete\"";
                        stringWriter.write(String.join("\t", hardDeleteValues));
                        stringWriter.write("\n");
                    }
                }
            }

            // Iterate over the accounts in the JSON response
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject account = jsonArray.getJSONObject(i);
                String accountId;
                if (account.has("node")) {
                    accountId = String.valueOf(account.getJSONObject("node").getInt("accountId"));
                } else {
                    accountId = String.valueOf(account.getInt("accountId"));
                }

                // Write data for the account
                String[] values = keys.stream()
                        .map(key -> {
                            String[] nestedKeys = key.split("\\.");
                            String[] newKeys = Arrays.copyOfRange(nestedKeys, containEdges ? 2 : 1, nestedKeys.length);
                            Object value = getValue(account, newKeys);
                            return (value != null) ? "\"" + value.toString() + "\"" : "\"\"";
                        })
                        .toArray(String[]::new);

                // Add eventType value
                String eventTypeValue = (String) eventTypeMap.getOrDefault(accountId, "");
                values = Arrays.copyOf(values, values.length + 1);
                values[values.length - 1] = "\"" + eventTypeValue + "\"";

                // Replace null values with ""
                for (int j = 0; j < values.length; j++) {
                    if ("\"null\"".equals(values[j])) {
                        values[j] = "\"\"";
                    }
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