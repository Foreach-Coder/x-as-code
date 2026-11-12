package io.xac.systemd.spec.validators;

import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.List;

import io.xac.systemd.spec.ServiceSpec;
import io.xac.systemd.spec.Validator;
import io.xac.systemd.spec.Violation;

/**
 * logs.file 只想接受绝对路径：相对路径会相对服务进程的 workdir 解析，而 workdir 可以变，
 * 同一个配置在不同机器上就会指向不同文件。这里不碰文件系统，文件是否存在由 logs 运行时判断。
 */
public final class LogsValidator implements Validator {

    @Override
    public void validate(ServiceSpec spec, List<Violation> out) {
        if (spec.logFile == null) {
            return;
        }
        boolean absolute;
        try {
            absolute = Paths.get(spec.logFile).isAbsolute();
        } catch (InvalidPathException e) {
            absolute = false;
        }
        if (!absolute) {
            out.add(new Violation("logs.file", "must be an absolute path"));
        }
    }
}
