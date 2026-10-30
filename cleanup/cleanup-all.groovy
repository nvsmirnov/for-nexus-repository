// This script will perform cleanup tasks for all blob stores and docker repos
// It is handy if you have a lot of repos or blob stores
// Without this automation, you'll need to create a set of tasks in GUI for each docker repo and each blob store

// For this script to work, you need to enable scripts in Nexus (potentially dangerous)
// To add script to Nexus, do this:
// - Enable scripts creation in Nexus (potentially dangerous):
//   Add to /nexus-data/etc/nexus.properties this line:
//     nexus.scripts.allowCreation=true
//   Restart Nexus
// - In GUI, Scripts/Tasks, add task of type "Admin - Execute script"
//   Name it something like "Docker Usage Stats"
//   Schedule - not too often, usually Daily is ok
//   Copy-paste this file to Source field, hit Save
// - Check if script task runs correctly, set up schedule
// - Disable scripts creation in Nexus which was enabled on first step
//   (remove nexus.scripts.allowCreation=true from /nexus-data/etc/nexus.properties)
// - Restart Nexus
// - Chack if task runs
// Note that script can be time and resource-consuming (for me, for tens of thousands of blobs it runs over seconds or tens of seconds, it can depend on many factors)
// You can filter repository names by setting repoFilter below (if there are only comparably small repos there, it will be lighter and faster)
// (c) Nikita Smirnov

import org.sonatype.nexus.repository.Repository
import org.sonatype.nexus.repository.storage.StorageFacet
import org.sonatype.nexus.blobstore.api.BlobStoreManager

def scheduler = container.lookup('org.sonatype.nexus.scheduling.TaskScheduler')

def blobStoreManager = container.lookup(org.sonatype.nexus.blobstore.api.BlobStoreManager.class.name)

// helper: run-now + подождать окончания
def runNow = { String typeId, Map<String,Object> props, String name ->
  log.info("cleanup-all script: Task ${name}: starting")
  def cfg = scheduler.createTaskConfigurationInstance(typeId)
  cfg.setName(name)
  props.each { k, v ->
    if (v instanceof Boolean) cfg.setBoolean(k, v)
    else if (v != null)       cfg.setString(k, v.toString())
  }
  def t = scheduler.submit(cfg)     // немедленный запуск
  // ждать завершения, чтобы сохранять порядок
  while (t.getCurrentState().getState().name() in ['WAITING','RUNNING']) {
    sleep(1000)
  }
  log.info("cleanup-all script: Task ${name}: ${t.getLastRunState()} ${t.getMessage() ?: ''}")
}

// Task Type: "Admin - Cleanup repositories using their associated policies"
runNow('repository.cleanup', [:], "repositories-cleanup")

// docker repos specific tasks
repository.repositoryManager.browse().each { Repository repo ->
    if (repo.format?.value == "docker" && repo.type?.value != "group" ) {

        // Task Type: "Docker - Delete incomplete uploads"
        def AGE_HOURS = '24'
        runNow('repository.docker.upload-purge', [repositoryName: repo.name, age: AGE_HOURS], "docker-upload-purge(${repo.name}, ${AGE_HOURS}h)")

        // Task Type: "Docker - Delete unused manifests and images"
        runNow('repository.docker.gc', [repositoryName: repo.name], "docker-gc:${repo.name}")
    }
}

// blobstores cleanup
blobStoreManager.browse().each { bs ->
    def name = bs.getBlobStoreConfiguration().getName()

    // Task Type: "Admin - Delete blob store temporary files"
    runNow('blobstore.delete-temp-files', [blobstoreName: name], "blob-temp-clean(${name})")

    // Task Type: "Admin - Compact blob store" - final freeing of disk space
    runNow('blobstore.compact',           [blobstoreName: name], "blob-compact(${name})")
}
