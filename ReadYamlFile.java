import graphql.language.*;
import graphql.parser.Parser;
import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            String q1 = (String) yamlData.get("q5");
            System.out.println(q1);
            // response
            String r1 = (String) yamlData.get("r5");
            System.out.println(r1);

            // Regular expression pattern to match the operation name
            Pattern pattern = Pattern.compile("^\\s*(query|mutation|subscription)\\s+(\\w+).*", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(q1);

            if (matcher.find()) {
                String operationType = matcher.group(1);
                String operationName = matcher.group(2);
                System.out.println("Operation Type: " + operationType);
                System.out.println("Operation Name: " + operationName);
            } else {
                System.out.println("Operation name not found.");
            }

            // Create a HashMap with String keys and Object values
            Map<String, Object> eventMap = new HashMap<>();

            // Put dummy values into the map
            eventMap.put("181834", "Insert");
            eventMap.put("181835", "Delete");
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
                        .forEach(field -> extractKeys(field, "", queryName, keys)));

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
        if (field.getName().equals("edges") || field.getName().equals("node")) {
            containEdges = true;
        }

        if (field.getSelectionSet() != null) {
            field.getSelectionSet().getSelections().stream()
                    .filter(selection -> selection instanceof Field)
                    .map(selection -> (Field) selection)
                    .forEach(nestedField -> extractKeys(nestedField, prefix + (prefix.isEmpty() ? "" : ".") + field.getName(), queryName, keys));
        } else {
            keys.add(prefix.isEmpty() ? field.getName() : prefix + "." + field.getName());
        }
    }

    /**
     * Processes a JSON response, extracts account information, and writes it to a text file.
     * It includes logic to handle accounts marked for deletion and ensures that data is written in a tab-separated format.
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
                    // Check if the account ID is found in the JSON response
                    boolean accountFound = false;
                    for (int i = 0; i < jsonArray.length(); i++) {
                        JSONObject account = jsonArray.getJSONObject(i);
                        String accountIdInResponse = extractAccountIdFromAccount(account);
                        if (accountId.equals(accountIdInResponse)) {
                            accountFound = true;
                            break;
                        }
                    }
                    // If the account ID is not found, write a "Hard Delete" entry
                    if (!accountFound) {
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
                String accountId = extractAccountIdFromAccount(account);

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
     * Recursively searches for a specific account ID within a JSON object and its nested objects.
     * If the account ID is found, it returns the corresponding JSON object.
     * If the account ID is not found, it returns null.
     *
     * @param jsonObject The JSON object to search within.
     * @param accountId The account ID to search for.
     * @return The JSON object containing the account ID if found, otherwise null.
     */
        public static JSONObject findAccountById(JSONObject jsonObject, String accountId) {
            // Check if the current JSON object contains the accountId directly
            if (jsonObject.has(accountId)) {
                return jsonObject;
            }

            // If the current JSON object doesn't contain the accountId directly, check its nested objects
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = jsonObject.get(key);
                if (value instanceof JSONObject) {
                    // Recursively search nested objects
                    JSONObject result = findAccountById((JSONObject) value, accountId);
                    if (result != null) {
                        return result;
                    }
                } else if (value instanceof JSONArray) {
                    // If the value is an array, iterate over its elements and recursively search nested objects
                    JSONArray jsonArray = (JSONArray) value;
                    for (int i = 0; i < jsonArray.length(); i++) {
                        if (jsonArray.get(i) instanceof JSONObject) {
                            JSONObject result = findAccountById(jsonArray.getJSONObject(i), accountId);
                            if (result != null) {
                                return result;
                            }
                        }
                    }
                }
            }

            // If accountId is not found in the current object or its nested objects, return null
            return null;
        }
    /**
     * Extracts the account ID from a JSON object by using the findAccountById method
     * to search for the account ID within the given JSON object.
     *
     * @param account The JSON object representing the account.
     * @return The account ID if found, otherwise null.
     */
        public static String extractAccountIdFromAccount(JSONObject account) {
            // Call the findAccountById method with the account JSONObject and "accountId"
            JSONObject accountObject = findAccountById(account, "accountId");

            // If the accountObject is found, return its accountId value
            if (accountObject != null && accountObject.has("accountId")) {
                return accountObject.get("accountId").toString();
            }

            // If accountId is not found, return null
            return null;
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