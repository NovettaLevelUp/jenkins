package jenkins.model;

import static hudson.cli.CLICommandInvoker.Matcher.failedWith;
import static hudson.cli.CLICommandInvoker.Matcher.hasNoStandardOutput;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import hudson.PluginWrapper;
import hudson.cli.CLICommandInvoker;
import hudson.cli.DisablePluginCommand;
import hudson.model.Descriptor;
import hudson.model.MyView;
import hudson.model.User;
import hudson.model.View;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import hudson.tasks.Shell;
import java.io.IOException;
import java.net.HttpURLConnection;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.recipes.WithPlugin;

/**
 * As Jenkins.MANAGE can be enabled on startup with jenkins.security.ManagePermission property, we need a test class
 * with this property activated.
 */
// TODO move tests to indicated test classes when we no longer need to set the system property
public class JenkinsManagePermissionTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @BeforeClass
    public static void enableManagePermission() {
        System.setProperty("jenkins.security.ManagePermission", "true");
    }

    @AfterClass
    public static void disableManagePermission() {
        System.clearProperty("jenkins.security.ManagePermission");
    }


    // -----------------------------
    // DisablePluginCommandTest
    @Issue("JENKINS-60266")
    @Test
    @WithPlugin({ "depender-0.0.2.hpi", "dependee-0.0.2.hpi"})
    public void managerCannotDisablePlugin() {

        //GIVEN a user with Jenkins.MANAGE permission
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.MANAGE).everywhere().to("manager")
        );

        //WHEN trying to disable a plugin
        assertThat(disablePluginsCLiCommandAs("manager", "dependee"), failedWith(6));
        //THEN it's refused and the plugin is not disabled.
        assertPluginEnabled("dependee");
    }

    /**
     * Disable a list of plugins using the CLI command.
     * @param user Username
     * @param args Arguments to pass to the command.
     * @return Result of the command. 0 if succeed, 16 if some plugin couldn't be disabled due to dependent plugins.
     */
    private CLICommandInvoker.Result disablePluginsCLiCommandAs(String user, String... args) {
        return new CLICommandInvoker(j, new DisablePluginCommand()).asUser(user).invokeWithArgs(args);
    }


    private void assertPluginEnabled(String name) {
        PluginWrapper plugin = j.getPluginManager().getPlugin(name);
        assertThat(plugin, is(notNullValue()));
        assertTrue(plugin.isEnabled());
    }

    // End of DisablePluginCommandTest
    //-------

    // -----------------------------
    //ComputerTest
    @Issue("JENKINS-60266")
    @Test
    public void dumpExportTableForbiddenWithoutAdminPermission() throws Exception {
        final String READER = "reader";
        final String MANAGER = "manager";
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ).everywhere().to(READER)
                .grant(Jenkins.MANAGE).everywhere().to(MANAGER)
                .grant(Jenkins.READ).everywhere().to(MANAGER)
        );
        j.createWebClient().login(READER).assertFails("computer/(built-in)/dumpExportTable", HttpURLConnection.HTTP_FORBIDDEN);
        j.createWebClient().login(MANAGER).assertFails("computer/(built-in)/dumpExportTable", HttpURLConnection.HTTP_FORBIDDEN);
    }

    // End of ComputerTest
    //-------

    // -----------------------------
    // HudsonTest
    @Issue("JENKINS-60266")
    @Test
    public void someGlobalConfigurationIsNotDisplayedWithManagePermission() throws Exception {
        //GIVEN a user with Jenkins.MANAGE permission
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.MANAGE, Jenkins.READ).everywhere().toEveryone());

        //WHEN the user goes to /configure page
        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        String formText = form.asNormalizedText();
        //THEN items restricted to ADMINISTER only should not be displayed.
        assertThat("Should be able to configure system message", formText, not(containsString("systemMessage")));
        assertThat("Should be able to configure project naming strategy", formText, not(containsString("useProjectNamingStrategy")));
        assertThat("Shouldn't be able to configure primary view", formText, not(containsString("primaryView")));
        assertThat("Shouldn't be able to configure # of executors", formText, not(containsString("executors")));
        assertThat("Shouldn't be able to configure Global properties", formText,
                not(containsString("Global properties")));
        assertThat("Shouldn't be able to configure Administrative monitors", formText, not(containsString(
                "Administrative "
                        + "monitors")));
        assertThat("Shouldn't be able to configure Shell", formText, not(containsString("Shell")));
    }

    @Issue("JENKINS-60266")
    @Test
    public void someGlobalConfigCanNotBeModifiedWithManagePermission() throws Exception {
        j.jenkins.addView(new MyView("testView", j.jenkins));

        //GIVEN the Global Configuration Form, with some changes unsaved
        int currentNumberExecutors = j.getInstance().getNumExecutors();
        String shell = getShell();
        View view = j.jenkins.getPrimaryView();
        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        form.getInputByName("_.numExecutors").setValueAttribute(""+(currentNumberExecutors+1));
        form.getInputByName("_.shell").setValueAttribute("/fakeShell");
        form.getSelectByName("primaryView").setSelectedAttribute("testView", true);

        // WHEN a user with only Jenkins.MANAGE permission try to save those changes
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.MANAGE, Jenkins.READ).everywhere().toEveryone());
        j.submit(form);
        // THEN the changes on fields forbidden to a Jenkins.MANAGE permission are not saved
        assertEquals("shouldn't be allowed to change the number of executors", currentNumberExecutors, j.getInstance().getNumExecutors());
        assertEquals("shouldn't be allowed to change the shell executable", shell, getShell());
        assertEquals("shouldn't be allowed to change the primary view", view, j.getInstance().getPrimaryView());
    }

    @Issue("JENKINS-60266")
    @Test
    public void globalConfigAllowedWithManagePermission() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.MANAGE, Jenkins.READ).everywhere().toEveryone());

        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        HtmlPage updated = j.submit(form);
        assertThat("User with Jenkins.MANAGE permission should be able to update global configuration",
                updated.getWebResponse(), hasResponseCode(HttpURLConnection.HTTP_OK));
    }

    @Issue("JENKINS-61457")
    @Test
    public void managePermissionCanChangeUsageStatistics() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                                                   .grant(Jenkins.MANAGE, Jenkins.READ).everywhere().toEveryone());

        boolean previousValue = j.jenkins.isUsageStatisticsCollected();
        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        form.getInputByName("_.usageStatisticsCollected").setChecked(!previousValue);
        j.submit(form);

        assertThat("Can set UsageStatistics", j.jenkins.isUsageStatisticsCollected(), not(previousValue));
    }

    private String getShell() {
        Descriptor descriptorByName = j.getInstance().getDescriptorByName("hudson.tasks.Shell");
        return ((Shell.DescriptorImpl) descriptorByName).getShell();
    }

    private static Matcher<WebResponse> hasResponseCode(final int httpStatus) {
        return new BaseMatcher<WebResponse>() {
            @Override
            public boolean matches(final Object item) {
                final WebResponse response = (WebResponse) item;
                return response.getStatusCode() == httpStatus;
            }

            @Override
            public void describeTo(final Description description) {
                description.appendText("Jenkins to return  ").appendValue(httpStatus);
            }

            @Override
            public void describeMismatch(Object item, Description description) {
                WebResponse response = (WebResponse) item;
                description.appendText("Response code was: ");
                description.appendValue(response.getStatusCode());
                description.appendText(" with error message: ");
                description.appendText(response.getStatusMessage());
                description.appendText("\n with headers ").appendValueList("", "\n    ", "", response.getResponseHeaders());
                description.appendText("\nPage content: ").appendValue(response.getContentAsString());
            }
        };
    }

    // End of HudsonTest
    //-------

    @Issue("JENKINS-63795")
    @Test
    public void managePermissionShouldBeAllowedToRestart() throws IOException {

        //GIVEN a Jenkins with 3 users : ADMINISTER, MANAGE and READ
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        User adminUser = realm.createAccount("Administer", "G0d");
        User manageUser = realm.createAccount("Manager", "TheB00S");
        User readUser = realm.createAccount("Reader", "BookW00rm");
        j.jenkins.setSecurityRealm(realm);

        ProjectMatrixAuthorizationStrategy authorizationStrategy = new ProjectMatrixAuthorizationStrategy();
        authorizationStrategy.add(Jenkins.ADMINISTER, adminUser.getId());

        authorizationStrategy.add(Jenkins.MANAGE, manageUser.getId());
        authorizationStrategy.add(Jenkins.READ, manageUser.getId());

        authorizationStrategy.add(Jenkins.READ, readUser.getId());
        j.jenkins.setAuthorizationStrategy(authorizationStrategy);

        //WHEN Asking for restart or safe-restart
        //THEN MANAGE and ADMINISTER are allowed but not READ
        CLICommandInvoker.Result result = new CLICommandInvoker(j, "restart").asUser(readUser.getId()).invoke();
        assertThat(result, allOf(failedWith(6),hasNoStandardOutput()));

        result = new CLICommandInvoker(j, "safe-restart").asUser(readUser.getId()).invoke();
        assertThat(result, allOf(failedWith(6),hasNoStandardOutput()));

        // We should assert that cli result is 0
        // but as restart is not allowed in JenkinsRule, we assert that it has tried to restart.
        result = new CLICommandInvoker(j, "restart").asUser(manageUser.getId()).invoke();
        assertThat(result, failedWith(1));
        assertThat(result.stderr(),containsString("RestartNotSupportedException"));

        result = new CLICommandInvoker(j, "safe-restart").asUser(manageUser.getId()).invoke();
        assertThat(result, failedWith(1));
        assertThat(result.stderr(),containsString("RestartNotSupportedException"));

        result = new CLICommandInvoker(j, "restart").asUser(adminUser.getId()).invoke();
        assertThat(result, failedWith(1));
        assertThat(result.stderr(),containsString("RestartNotSupportedException"));

        result = new CLICommandInvoker(j, "safe-restart").asUser(adminUser.getId()).invoke();
        assertThat(result, failedWith(1));
        assertThat(result.stderr(),containsString("RestartNotSupportedException"));
    }
}
