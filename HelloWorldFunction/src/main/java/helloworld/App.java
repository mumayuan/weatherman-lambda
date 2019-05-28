package helloworld;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3Client;
import org.json.JSONArray;
import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;

/**
 * Handler for requests to Lambda function.
 */
public class App implements RequestHandler<APIGatewayProxyRequestEvent, Object> {
    private static String bucket_name = "mq2lambda";

    static {
        System.out.println("----------System.getenv(accesskey------->" + System.getenv("accesskey"));
    }

    BasicAWSCredentials awsCreds = new BasicAWSCredentials(
            "xxx", "yyy"
    );

    public Object handleRequest(final APIGatewayProxyRequestEvent input, final Context context) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Custom-Header", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");

        boolean sample = false;
        try {


            // final String pageContents = this.getPageContents("https://checkip.amazonaws.com");
            Map<String, String> map = input.getQueryStringParameters();

            String location = "mq2"; //the default for testing

            if (map != null) {
                location = map.get("location");
            }


            AmazonS3 s3 = AmazonS3ClientBuilder.standard().withRegion("us-east-1")
                    .withCredentials(new AWSStaticCredentialsProvider(awsCreds))
                    .build();


            String weatherJson = null;
            String vegaJson = readS3File(s3, "vega.json");
            JSONObject weatherJsonObj = null;
            try {
                StringBuffer content = new StringBuffer();
                URL url = new URL("http://dataservice.accuweather.com/forecasts/v1/daily/5day/" + location + "?apikey=ADgKDhujgzeWoJzneLPd3jPlcDnaajLF");
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");


                int status = con.getResponseCode();

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(con.getInputStream()));
                String inputLine;

                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                    //System.out.println(">"+inputLine);
                }
                in.close();

                weatherJson = content.toString();

                weatherJsonObj = new JSONObject(weatherJson);
            } catch (Throwable t) {
                System.out.println("Bad request, probably exceeding allowed queries" + t);
                weatherJson = readS3File(s3, "weather.json");
                weatherJsonObj = new JSONObject(weatherJson);
                sample = true;
            }


            //assemble the response json object
            JSONObject responseJson = new JSONObject();

            JSONObject headline = (JSONObject) weatherJsonObj.get("Headline");
            responseJson.put("weather", headline.get("Text").toString());

            if (!sample) {
                responseJson.put("date", convertDate(headline.get("EffectiveDate").toString()));
            } else {
                responseJson.put("date", "Sample Data");
            }
            JSONArray fiveDays = new JSONArray();
            JSONArray forecasts = (JSONArray) weatherJsonObj.get("DailyForecasts");

            //add 5 days forecast into an array
            for (int loop = 0; loop < forecasts.length(); loop++) {
                JSONObject loopForcast = forecasts.getJSONObject(loop);

                String dateText = convertDate(loopForcast.get("Date").toString());

                JSONObject day = new JSONObject();

                day.put("date", dateText);

                day.put("dayIcon", getIconText((int) loopForcast.getJSONObject("Day").get("Icon")));
                day.put("dayText", loopForcast.getJSONObject("Day").get("IconPhrase"));


                day.put("nightIcon", getIconText((int) loopForcast.getJSONObject("Night").get("Icon")));
                day.put("nightText", loopForcast.getJSONObject("Night").get("IconPhrase"));


                String dayHigh = "" + loopForcast.getJSONObject("Temperature").getJSONObject("Maximum").get("Value");
                String dayLow = "" + loopForcast.getJSONObject("Temperature").getJSONObject("Minimum").get("Value");


                day.put("dayHigh", dayHigh);
                day.put("dayLow", dayLow);
                fiveDays.put(day);

                vegaJson = vegaJson.replaceAll("day" + loop, dateText);
                vegaJson = vegaJson.replaceAll("high" + loop, dayHigh);
                vegaJson = vegaJson.replaceAll("low" + loop, dayLow);
            }

            responseJson.put("fivedays", fiveDays);

            JSONObject vegaJsonObj = new JSONObject(vegaJson);
            responseJson.put("vega", vegaJsonObj);

            String jsonText = responseJson.toString();
            System.out.println("----------response------->" + jsonText);


            return new GatewayResponse(jsonText, headers, 200);
        } catch (Exception e) {
            System.out.println("--------error--------->" + e.getMessage());
            e.printStackTrace();
            return new GatewayResponse("{}", headers, 500);
        }
    }

    private String getPageContents(String address) throws IOException {
        URL url = new URL(address);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()))) {
            return br.lines().collect(Collectors.joining(System.lineSeparator()));
        }
    }

    private String getIconText(int icon) {

        String iconPrefix = icon < 10 ? "0" : "";
        return iconPrefix + icon;
    }

    private static String getParamsString(Map<String, String> params)
            throws UnsupportedEncodingException {
        StringBuilder result = new StringBuilder();

        for (Map.Entry<String, String> entry : params.entrySet()) {
            result.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            result.append("=");
            result.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            result.append("&");
        }

        String resultString = result.toString();
        return resultString.length() > 0
                ? resultString.substring(0, resultString.length() - 1)
                : resultString;
    }


    private String readS3File(AmazonS3 s3, String fileName) {
        String ret = "";
        try {
            S3Object o = s3.getObject(bucket_name, fileName);
            S3ObjectInputStream s3is = o.getObjectContent();
            ByteArrayOutputStream fos = new ByteArrayOutputStream();
            byte[] read_buf = new byte[1024];
            int read_len = 0;
            while ((read_len = s3is.read(read_buf)) > 0) {
                fos.write(read_buf, 0, read_len);
            }
            s3is.close();
            fos.close();


            ret = (fos.toString());
        } catch (AmazonServiceException e) {
            System.err.println(e.getErrorMessage());
            System.exit(1);
        } catch (FileNotFoundException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
        return ret;
    }

    /**
     * convert a 2019-05-22T02:00:00-05:00  into just month and day
     *
     * @param s
     * @return
     */
    private static String convertDate(String s) {
        DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("MM-dd");
        LocalDate date = LocalDate.parse(s, DateTimeFormatter.ISO_DATE_TIME);
        return date.format(outputFormatter);
    }
}
