package cn.sweater.rag.trigger.http;

import cn.sweater.rag.api.IRAGService;
import cn.sweater.rag.api.response.Response;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.PathResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;


@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/rag/")
public class RAGController implements IRAGService {
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "txt", "md",
            "go", "java", "py", "js", "ts",
            "yml", "yaml", "json", "xml",
            "properties", "sql", "sh"
    );


    @Resource
    private OllamaChatClient ollamaChatClient;
    @Resource
    private TokenTextSplitter tokenTextSplitter;
    @Resource
    private SimpleVectorStore simpleVectorStore;
    @Resource
    private PgVectorStore pgVectorStore;
    @Resource
    private RedissonClient redissonClient;

    @RequestMapping(value = "query_rag_tag_list", method = RequestMethod.GET)
    @Override
    public Response<List<String>> queryRagTagList() {
        RList<String> elements = redissonClient.getList("ragTag");
        return Response.<List<String>>builder()
                .code("0000")
                .info("调用成功")
                .data(elements)
                .build();
    }

    @RequestMapping(
            value = "file/upload",
            method = RequestMethod.POST,
            headers = "content-type=multipart/form-data"
    )
    @Override
    public Response<String> uploadFile(
            @RequestParam("ragTag") String ragTag,
            @RequestParam("file") List<MultipartFile> files) {

        log.info("上传知识库开始 {}", ragTag);

        for (MultipartFile file : files) {

            if (!isTextOrCodeFile(file)) {
                log.warn("跳过非文本/非代码文件: {}", file.getOriginalFilename());
                continue;
            }

            try {
                TikaDocumentReader documentReader =
                        new TikaDocumentReader(file.getResource());

                List<Document> documents = documentReader.get();
                if (documents == null || documents.isEmpty()) {
                    log.warn("文件无可解析内容: {}", file.getOriginalFilename());
                    continue;
                }

                List<Document> documentSplitterList =
                        tokenTextSplitter.apply(documents);

                documents.forEach(doc ->
                        doc.getMetadata().put("knowledge", ragTag));

                documentSplitterList.forEach(doc ->
                        doc.getMetadata().put("knowledge", ragTag));

                pgVectorStore.accept(documentSplitterList);

            } catch (Exception e) {
                log.error("解析文件失败: {}", file.getOriginalFilename(), e);
            }
        }

        RList<String> elements = redissonClient.getList("ragTag");
        if (!elements.contains(ragTag)) {
            elements.add(ragTag);
        }

        log.info("上传知识库完成 {}", ragTag);
        return Response.<String>builder()
                .code("0000")
                .info("调用成功")
                .build();
    }
    @RequestMapping(value = "analyze_git_repository", method = RequestMethod.POST)
    @Override
    public Response<String> analyzeGitRepository(@RequestParam String repoUrl,
                                                 @RequestParam String userName,
                                                 @RequestParam String token,
                                                 @RequestParam(required = false) String branch) throws Exception {
        String localPath = "./git-cloned-repo1";
        String repoProjectName = extractProjectName(repoUrl);
        log.info("克隆路径：{}", new File(localPath).getAbsolutePath());

        // 删除旧目录
        deleteDirectoryWithRetry(localPath);

        // clone 仓库
        CloneCommand cloneCommand = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(new File(localPath))
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(userName, token));

        // 如果传入 branch 参数，则切换分支
        if (branch != null && !branch.isBlank()) {
            cloneCommand.setBranch(branch);
            log.info("克隆指定分支: {}", branch);
        }

        try (Git git = cloneCommand.call()) {

            // 遍历文件上传到向量库
            Files.walkFileTree(Paths.get(localPath), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toString().contains(".git")) return FileVisitResult.CONTINUE;
                    if (!isTextOrCodeFile(file)) return FileVisitResult.CONTINUE;

                    log.info("{} 遍历解析路径，上传知识库: {}", repoProjectName, file.getFileName());
                    try {
                        TikaDocumentReader reader = new TikaDocumentReader(new PathResource(file));
                        List<Document> documents = reader.get();
                        if (documents.isEmpty()) return FileVisitResult.CONTINUE;

                        List<Document> documentSplitterList = tokenTextSplitter.apply(documents);
                        documents.forEach(doc -> doc.getMetadata().put("knowledge", repoProjectName));
                        documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", repoProjectName));
                        pgVectorStore.accept(documentSplitterList);
                    } catch (Exception e) {
                        log.error("上传失败: {}", file.getFileName(), e);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        // clone 完毕后删除目录
        deleteDirectoryWithRetry(localPath);

        // 更新 Redis tag 列表
        RList<String> elements = redissonClient.getList("ragTag");
        if (!elements.contains(repoProjectName)) elements.add(repoProjectName);

        log.info("遍历解析路径，上传完成: {}", repoUrl);
        return Response.<String>builder().code("0000").info("调用成功").build();
    }

    // 删除目录，失败时重试
    private void deleteDirectoryWithRetry(String path) throws IOException, InterruptedException {
        File dir = new File(path);
        if (!dir.exists()) return;

        int retries = 3;
        for (int i = 0; i < retries; i++) {
            try {
                FileUtils.deleteDirectory(dir);
                return; // 删除成功
            } catch (IOException e) {
                log.warn("删除目录失败，重试 {}/{}: {}", i + 1, retries, e.getMessage());
                Thread.sleep(500); // 等半秒再试
            }
        }
        // 最后一次尝试，如果仍然失败直接抛异常
        FileUtils.deleteDirectory(dir);
    }

    private String extractProjectName(String repoUrl) {
        String[] parts = repoUrl.split("/");
        String projectNameWithGit = parts[parts.length - 1];
        return projectNameWithGit.replace(".git", "");
    }
    private boolean isTextOrCodeFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return false;
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.contains(".")) {
            return false;
        }

        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            return false;
        }

        return true;
    }
    private boolean isTextOrCodeFile(Path path) {
        if (path == null || Files.isDirectory(path)) {
            return false;
        }

        String filename = path.getFileName().toString();
        if (!filename.contains(".")) {
            return false;
        }

        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            return false;
        }

        return true;
    }


}
