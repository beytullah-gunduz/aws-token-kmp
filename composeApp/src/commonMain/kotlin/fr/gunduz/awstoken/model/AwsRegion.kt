package fr.gunduz.awstoken.model

enum class AwsRegion(val code: String, val displayName: String) {
    US_EAST_1("us-east-1", "US East (N. Virginia)"),
    US_EAST_2("us-east-2", "US East (Ohio)"),
    US_WEST_1("us-west-1", "US West (N. California)"),
    US_WEST_2("us-west-2", "US West (Oregon)"),
    EU_WEST_1("eu-west-1", "Europe (Ireland)"),
    EU_WEST_2("eu-west-2", "Europe (London)"),
    EU_WEST_3("eu-west-3", "Europe (Paris)"),
    EU_CENTRAL_1("eu-central-1", "Europe (Frankfurt)"),
    EU_NORTH_1("eu-north-1", "Europe (Stockholm)"),
    AP_NORTHEAST_1("ap-northeast-1", "Asia Pacific (Tokyo)"),
    AP_SOUTHEAST_1("ap-southeast-1", "Asia Pacific (Singapore)"),
    AP_SOUTHEAST_2("ap-southeast-2", "Asia Pacific (Sydney)"),
    AP_SOUTH_1("ap-south-1", "Asia Pacific (Mumbai)"),
    CA_CENTRAL_1("ca-central-1", "Canada (Central)"),
    SA_EAST_1("sa-east-1", "South America (São Paulo)"),
    ;

    companion object {
        val DEFAULT = EU_WEST_1
        fun fromCode(code: String?): AwsRegion = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}
