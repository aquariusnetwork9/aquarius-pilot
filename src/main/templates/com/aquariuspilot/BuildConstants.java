package ${maven_group};

// These constants are filled in from gradle.properties before compilation
public class BuildConstants {

    public static final String VERSION = "${version}";

    public static final String MC_VERSION = "${mc_version}";

    public static final String PLUGIN_ID = "${plugin_id}";
}
