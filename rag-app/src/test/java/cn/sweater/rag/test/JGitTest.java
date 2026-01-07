package cn.sweater.rag.test;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.PathResource;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class JGitTest {

    @Resource
    private OllamaChatClient ollamaChatClient;
    @Resource
    private TokenTextSplitter tokenTextSplitter;
    @Resource
    private SimpleVectorStore simpleVectorStore;
    @Resource
    private PgVectorStore pgVectorStore;

    @Test
    public void testCloneRepoSSHBranch() throws Exception {
        // 仓库信息（SSH 格式）
        String repoURL = "git@github.com:xjyisok/KVEngine.git";
        String branch = "bitcask-go-LRU"; // 指定分支

        // 克隆到本地路径
        String localPath = "D:\\javacode\\WebProj\\cloned-repo";
        File localDir = new File(localPath);
        if (localDir.exists()) {
            FileUtils.deleteDirectory(localDir);
        }
        log.info("克隆路径：" + localDir.getAbsolutePath());

        // 使用命令行 git clone
        ProcessBuilder pb = new ProcessBuilder(
                "git", "clone", "-b", branch, repoURL, localPath
        );
        pb.inheritIO(); // 输出日志到控制台
        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode == 0) {
            log.info("克隆成功！");
        } else {
            log.error("克隆失败，exitCode=" + exitCode);
        }
    }

    @Test
    public void test_file() throws IOException {
        Files.walkFileTree(Paths.get("./cloned-repo"), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {

                // 1️⃣ 跳过 .git 文件夹
                if (file.toString().contains(".git")) {
                    return FileVisitResult.CONTINUE;
                }

                // 2️⃣ 只处理文本文件，可根据后缀过滤
                String filename = file.getFileName().toString().toLowerCase();
                if (!filename.endsWith(".go") && !filename.endsWith(".md") && !filename.endsWith(".txt")) {
                    return FileVisitResult.CONTINUE;
                }

                log.info("文件路径:{}", file.toString());

                PathResource resource = new PathResource(file);
                TikaDocumentReader reader = new TikaDocumentReader(resource);

                List<Document> documents = reader.get();
                if (documents.isEmpty()) {
                    return FileVisitResult.CONTINUE;
                }

                List<Document> documentSplitterList = tokenTextSplitter.apply(documents);

                documents.forEach(doc -> doc.getMetadata().put("knowledge", "KVEngine"));
                documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", "KVEngine"));

                pgVectorStore.accept(documentSplitterList);

                return FileVisitResult.CONTINUE;
            }
        });
    }


}
