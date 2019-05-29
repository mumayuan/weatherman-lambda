# AWS Lambda

This is a java based Lambda implementation. The Web UI will send user chosen location to API Gateway, and API Gate will invoke the lambda with the location information.

The lambda function will assemble the reponse from external web service API (AccuWeather) and AWS services. To showcase this concept, I built a Vega chart using a template stored in S3. 

The response include:
* Current eather status, or warnings 
* An array of daily status for 5 days 
* A vega Json spec for temperature trend for 5 days.

