package com.cdk101;

import software.amazon.awscdk.core.App;

public class OktankApp {
    public static void main(final String[] args) {
        App app = new App();

        // Create basic network
        new BasicNetworkStack(app, "BasicNetworkStack");

        app.synth();
    }
}
