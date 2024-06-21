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
    public static final String ACCOUNTIDKEY = "accountId";

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
            String q1 = (String) yamlData.get("q7");
            System.out.println(q1);
            // response
            String r1 = (String) yamlData.get("r7");
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
            eventMap.put("23980", "Delete");
            eventMap.put("129583", "Insert");
            eventMap.put("23", "Delete");
            eventMap.put("98", "Delete");
            eventMap.put("66", "Delete");

            List<String> headers = extractKeysFromQuery(q1, queryName);
            System.out.println(headers);
            storeDataInTXT(r1, headers, queryName,eventMap);
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
     * @param eventTypeMap write the values and header
     */
    public static void storeDataInTXT(String jsonResponse, List<String> keys, String queryName, Map<String, Object> eventTypeMap) {
        StringWriter stringWriter = new StringWriter();

        try {
            // Parse JSON data
            JSONObject jsonObject = new JSONObject(jsonResponse);
            JSONArray jsonArray;
            if (containEdges) {
                jsonArray = jsonObject.getJSONObject("data").getJSONObject(queryName).getJSONArray("edges");
            } else {
                jsonArray = jsonObject.getJSONObject("data").getJSONArray(queryName);
            }
            Map<String, Boolean> accountsFound = new HashMap<>();

            // Create a new list for headers
            List<String> headers = new ArrayList<>(keys);
            headers.add("eventType");

            stringWriter.write(String.join("\t", headers));
            stringWriter.write("\n");

            BufferedWriter writer = new BufferedWriter(new FileWriter("output.csv"));
            writer.write(String.join(",", keys));
            writer.newLine();

            // Iterate over the accounts in the JSON response
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject account = jsonArray.getJSONObject(i);
                String accountId = null;
                List<Integer> nestedArrSizes = new ArrayList<Integer>();

                // Get the size of all the nested JSONArray present inside this account
                for (String key : keys) {
                    String[] nestedKeys = key.split("\\.");
                    String[] newKeys = Arrays.copyOfRange(nestedKeys, containEdges ? 2 : 1, nestedKeys.length);
                    Object value = getValue(account, newKeys, null);
                    if (value instanceof JSONArray) {
                        nestedArrSizes.add(((JSONArray) value).length());
                    }
                }

                //Maximum size of any nested JSONArray
                int maxSize = nestedArrSizes.stream()
                        .mapToInt(Integer::intValue)
                        .max()
                        .orElse(1);

                for (int j = 0; j < maxSize; j++) {
                    int keysIndex = 0;
                    // Write data for the account
                    List<String> values = new ArrayList<>();

                    for (String key : keys) {
                        String[] nestedKeys = key.split("\\.");
                        String[] newKeys = Arrays.copyOfRange(nestedKeys, containEdges ? 2 : 1, nestedKeys.length);
                        Object value = getValue(account, newKeys, j);
                        values.add((value != null && !value.toString().equals("null")) ? "\"" + value.toString() + "\"" : "\"\"");

                        // Check if the current key is accountId
                        if (accountId == null && nestedKeys != null && nestedKeys.length > 0 && nestedKeys[nestedKeys.length - 1].equals(ACCOUNTIDKEY)) {
                            accountId = (value != null) ? value.toString() : null;
                            accountsFound.put(accountId, true);
                        }

                        //if last iteration of for loop add "insert" or "delete"
                        if (keysIndex == keys.size() - 1) {
                            String eventType = (accountId != null) ? (String) eventTypeMap.getOrDefault(accountId, "") : "";
                            values.add("\"" + eventType + "\"");
                        }
                        keysIndex++;
                    }
                    // Print values
                    System.out.println(String.join("\t", values));
                    stringWriter.write(String.join("\t", values));
                    stringWriter.write("\n");

                    writer.write(String.join(",", values));
                    writer.newLine();
                }
            }
            addHardDelete(stringWriter, keys, eventTypeMap, accountsFound);
            stringWriter.close();
            writer.close();

            String result = stringWriter.toString();
            System.out.println("Data has been written to output.csv");
            System.out.println(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Adds "Hard Delete" entries to the result if the accountId is present in the eventTypeMap but not found in the JSON response.
     * If an accountId is marked for deletion but not found in the JSON response, it writes a "Hard Delete" entry to the provided StringWriter.
     *
     * @param stringWriter   The StringWriter to append the "Hard Delete" entries to the CSV.
     * @param keys           The list of keys corresponding to the JSON structure, used to determine the number of columns.
     * @param eventTypeMap   A map containing account IDs as keys and their event types (e.g., "Insert", "Delete") as values.
     * @param accountsFound  A map tracking account IDs that are already found in the JSON response. The keys are account IDs, and values are booleans indicating whether the account is found.
     */
    private static void addHardDelete(StringWriter stringWriter, List<String> keys, Map<String, Object> eventTypeMap, Map<String, Boolean> accountsFound) {
        for (String accountId : eventTypeMap.keySet()) {
            if (eventTypeMap.get(accountId).equals("Delete")) {
                boolean accountFound = accountsFound.containsKey(accountId);

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
    }

    /**
     * Recursively gets nested values from a JSON object.
     *
     * @param jsonObject The JSON object.
     * @param keys       The array of keys representing the nested structure.
     * @param arrayIndex If nested JSONArray is present then use this index to iterate
     * @return The value obtained from the JSON object.
     */
    private static Object getValue(JSONObject jsonObject, String[] keys, Integer arrayIndex) {
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
                    if (arrayIndex != null){
                        if(jsonArray.isNull(arrayIndex)) return null;
                        currentObject = jsonArray.getJSONObject(arrayIndex);
                    }else{
                        return jsonArray;
                    }

                }
            } else {
                return value;
            }
        }
        return currentObject;
    }
}