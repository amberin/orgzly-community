package com.orgzly.android.git;

import org.eclipse.jgit.api.TransportCommand;

public interface GitTransportSetter extends AutoCloseable {
    public TransportCommand setTransport(TransportCommand tc);
}
