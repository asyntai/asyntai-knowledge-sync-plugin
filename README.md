# Asyntai Knowledge Sync

Keep your [Asyntai](https://asyntai.com) AI chatbot up to date with your documentation. This plugin adds one build step that sends the docs in your workspace to the Asyntai knowledge base, so the chatbot on your website answers from the pages you just merged.

- A new file becomes a new knowledge base entry.
- A changed file replaces its own entry.
- A file with the same text is left alone, so a build costs nothing against your daily limit.
- With `prune` on, the entry of a deleted file is removed.

Every build page shows a table of the pages the step added, updated, left alone or removed.

## Getting started

1. In Asyntai, open **Settings > API** and copy your API key.
2. In Jenkins, open **Manage Jenkins > Credentials** and add a **Secret text** credential with your key. Give it the ID `asyntai-api-key`.
3. Add the step after your checkout.

### Pipeline

```groovy
pipeline {
  agent any
  stages {
    stage('Sync docs') {
      steps {
        asyntaiKnowledgeSync credentialsId: 'asyntai-api-key',
          files: 'docs/**/*.md'
      }
    }
  }
}
```

### Freestyle

Press **Add build step**, choose **Asyntai Knowledge Sync**, and pick your credential. **Test connection** checks the key before you save.

## Parameters

| Parameter | Default | Description |
| --- | --- | --- |
| `credentialsId` | required | The Secret text credential that holds your Asyntai API key. |
| `files` | `docs/**/*.md` | Ant-style patterns of the files to send, one per line or separated by commas. |
| `excludes` | empty | Ant-style patterns of the files to leave out, for example `docs/drafts/**`. |
| `titlePrefix` | empty | Text put in front of every entry title. Use it when several repositories feed one chatbot. |
| `prune` | `false` | Delete entries whose file is gone. Only touches entries that carry `titlePrefix`, so it needs one. |
| `dryRun` | `false` | List the changes and send nothing. |
| `rootDirectory` | the workspace | The folder the patterns start from, relative to the workspace. |
| `websiteId` | empty | The Asyntai website to fill. Empty means the primary website of the account. |
| `apiUrl` | `https://asyntai.com` | The Asyntai address. Leave the default. |

The entry title is the file path relative to `rootDirectory`, with `titlePrefix` in front. The next build finds the entry again by that title.

## Examples

Several repositories feeding one chatbot, each removing only its own pages:

```groovy
asyntaiKnowledgeSync credentialsId: 'asyntai-api-key',
  files: 'docs/**/*.md',
  titlePrefix: 'product-docs/',
  prune: true
```

A preview on pull requests in a multibranch Pipeline, and the real sync on every other branch build:

```groovy
asyntaiKnowledgeSync credentialsId: 'asyntai-api-key',
  files: 'docs/**/*.md',
  dryRun: env.CHANGE_ID != null
```

Built documentation, for example a static site generated in an earlier stage:

```groovy
asyntaiKnowledgeSync credentialsId: 'asyntai-api-key',
  rootDirectory: 'site',
  files: '**/*.html'
```

## Requirements

- Jenkins 2.528.3 or later.
- An Asyntai account on the Starter plan or higher. The plugin itself is free.

Full guide: https://asyntai.com/documentation/integrations/jenkins/

## Contributing

Report issues and send pull requests on GitHub. Build and test with:

```
mvn verify
```

## License

[MIT](LICENSE.md)
