package nu.marginalia.control.fsm.monitor;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import nu.marginalia.db.storage.FileStorageService;
import nu.marginalia.db.storage.model.FileStorage;
import nu.marginalia.db.storage.model.FileStorageId;
import nu.marginalia.mqsm.StateFactory;
import nu.marginalia.mqsm.graph.AbstractStateGraph;
import nu.marginalia.mqsm.graph.GraphState;
import nu.marginalia.mqsm.graph.ResumeBehavior;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Singleton
public class FileStorageMonitorFSM extends AbstractStateGraph {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    // STATES

    private static final String INITIAL = "INITIAL";
    private static final String MONITOR = "MONITOR";
    private static final String PURGE = "PURGE";
    private static final String END = "END";
    private final FileStorageService fileStorageService;


    @Inject
    public FileStorageMonitorFSM(StateFactory stateFactory,
                                 FileStorageService fileStorageService) {
        super(stateFactory);
        this.fileStorageService = fileStorageService;
    }

    @GraphState(name = INITIAL, next = MONITOR)
    public void init() {
    }

    @GraphState(name = MONITOR, resume = ResumeBehavior.RETRY)
    public void monitor() throws Exception {

        for (;;) {
            Optional<FileStorage> toDeleteOpt = fileStorageService.findFileStorageToDelete();

            if (toDeleteOpt.isEmpty()) {
                TimeUnit.SECONDS.sleep(10);
            }
            else {
                transition(PURGE, toDeleteOpt.get().id());
            }
        }
    }

    @GraphState(name = PURGE, next = MONITOR, resume = ResumeBehavior.RETRY)
    public void purge(FileStorageId id) throws Exception {
        var storage = fileStorageService.getStorage(id);
        logger.info("Deleting {} ", storage.path());
        Path path = storage.asPath();

        if (Files.exists(path)) {
            FileUtils.deleteDirectory(path.toFile());
        }

        fileStorageService.removeFileStorage(storage.id());
    }
}
