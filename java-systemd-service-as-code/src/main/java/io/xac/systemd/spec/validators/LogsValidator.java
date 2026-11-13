package io.xac.systemd.spec.validators;

import java.util.List;

import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Validator;
import io.xac.systemd.spec.Violation;

/**
 * logs.file 相对路径按包目录解析（见 {@link ServiceSpec#logFilePath}），这里只拒绝无法解析的路径。
 * 文件是否存在由 logs 运行时判断，校验不碰文件系统。
 */
public final class LogsValidator implements Validator {

    @Override
    public void validate(ServiceSpec spec, List<Violation> out) {
        if (spec.logFile != null && spec.logFilePath == null) {
            out.add(new Violation("logs.file", "invalid path"));
        }
    }
}
