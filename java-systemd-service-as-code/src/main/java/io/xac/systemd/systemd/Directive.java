package io.xac.systemd.systemd;

import io.xac.systemd.spec.ServiceSpec;

public interface Directive {

    /** 返回一行 unit 指令；null 表示这条指令不出现。 */
    String render(ServiceSpec spec);
}
