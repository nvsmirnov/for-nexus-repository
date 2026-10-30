// This script will create prometheus metrics file with docker layers sizes stored by Nexus OSS.
// The metrics are approximate because of nature of how docker layers are stored.
// These metrics are just to give a clue which images (or image groups) are using much space in your Nexus.
// This script is designed to run by Nexus from Tasks.
// It will create file metrics/dockerBlobSizes.prom under nexus-data directory; overwrites it on every run.
// The report file will be ready for export to prometheus as a static (i.e. with nginx)
// It can'be exported with node exporter because it contains timestamps.
// The timestamps in report is needed for prometheus to ignore the data which was already collected.

// For this script to work, you need to enable scripts in Nexus (potentially dangerous)
// To add script to Nexus, do this:
// - Enable scripts in Nexus (potentially dangerous):
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

import org.sonatype.nexus.repository.storage.StorageFacet
import org.sonatype.nexus.repository.Repository
import org.sonatype.nexus.repository.storage.Asset
import groovy.json.JsonOutput

import org.sonatype.nexus.common.app.ApplicationDirectories
import org.sonatype.nexus.internal.app.ApplicationDirectoriesImpl
import java.nio.file.*

import groovy.json.JsonSlurper

def max_blob_refs = 0 // 5 // ignore layers which are used by more than N images, so "base" images used by many others will not duplicat in statistics. 0 to ignore
def top_N_only = 0 // show only top N lines of images. 0 to disable and show all

def filename = "dockerBlobSizes.prom"
def subdirname = "metrics" // directory under nexus-data

def repoFormatFilter = null // ['docker','maven2','nuget'] // filter only these repo types, null to not to filter
def repoFilter = null // ['maven-hosted'] // filter only these repos, null to not to filter

now = new Date()
nowMs = now.getTime()

ApplicationDirectories applicationDirectories = (ApplicationDirectories)container.lookup(ApplicationDirectoriesImpl.class.name)
def metricsDir = applicationDirectories.getTemporaryDirectory().toPath().getParent().resolve('metrics')
java.nio.file.Files.createDirectories(metricsDir) // should not fail if already exists; but can fail if file named metrics exists
def resultsFile = metricsDir.resolve(filename).toFile()

def readmeFile = metricsDir.resolve('README.md').toFile()
readmeFile.write("This directory and contents was created by sript which run from Nexus tasks.\n")
readmeFile.write("(System / Tasks / Docker Usage Stats)\n")
readmeFile.write("Last update try: ${now.format("yyyy-MM-dd HH:mm:ss")}\n")

resultsFile.write('') // overwriting; comment to have history for debugging, etc

// docker layers; top key is repository name, second is docker image name
def layers = [:].withDefault{ [:].withDefault{ [ "size": 0L, "ref_count": 0L, "refs": [:].withDefault{ 0L } ] } }
// other assets; top key is repository name, second is group name; no per-asset stats (it will be too much probably)
def assets = [:].withDefault{ [:].withDefault{ [ "size": 0L ] } }

def repo_blobstore = [:] // map repo: blobstore_name

repository.repositoryManager.browse().each { Repository repo ->
    if (repoFormatFilter && !(repo.format?.value in repoFormatFilter)) return
    if (repoFilter && !(repo.name in repoFilter)) return
    
    repo_blobstore[repo.name] = repo.configuration.attributes['storage']['blobStoreName']

    StorageFacet storageFacet = repo.facet(StorageFacet)
    def tx = storageFacet.txSupplier().get()
    
    try {
        tx.begin()

        if( repo.format?.value == 'docker' ) {
            // docker repo - special algorithm to count sizes
            def image_count = 0
            tx.browseAssets(tx.findBucket(repo)).each { asset ->

                def m = (asset.name() =~ /^v2\/(?:library\/)?(.+)\/manifests\/sha256\:.+$/)
                if ( ! m.matches() ) return
                def image = m.group(1)
                if ( ! image ) return

                image_count += 1

                def blob = tx.getBlob(asset.blobRef())
                if (!blob) return
                def json = new JsonSlurper().parseText(blob.getInputStream().getText('UTF-8'))
                if (json?.layers) {
                    json.layers.each { layer ->
                        def digest = layer.digest
                        def l=layers[repo.name][digest]
                        l.size = layer.size
                        l.ref_count += 1
                        if (!max_blob_refs || (l.ref_count <= max_blob_refs)) { // not adding if there are too much of it
                            l.refs[image] += 1
                        }
                    }
                }
            }
        } else {
            // non-docker repo - just get the asset size
            def components = tx.browseComponents(tx.findBucket(repo))
            components.each { component ->
                def name = component.name()
                def group = component.group() ?: "NO_GROUP"
                // def fullName = group ? "${group}/${name}" : name

                def totalSize = 0L
                tx.browseAssets(component).each { asset ->
                    totalSize += asset.size()
                }
                assets[repo.name][group].size += totalSize
            }
        }
    } finally {
        tx.close()
    }
}

// docker stats
stat_by_image = [:].withDefault{ [:].withDefault{ 0L } }
stat_by_image_group = [:].withDefault{ [:].withDefault { 0L } }
layers.each { repo_name, repo_layers ->
    repo_layers.each { layer_name, layer_info ->
        if (max_blob_refs && layer_info.ref_count > max_blob_refs) return
        layer_info.refs.each{ image, image_refs ->
            stat_by_image[repo_name][image] += layer_info.size
            
            def parts = image.tokenize('/')
            if (parts.size() > 1) {
                image_group = parts[0]
                stat_by_image_group[repo_name][image_group] += layer_info.size
            }
        }
    }
}
if (stat_by_image) {
    resultsFile.append("# HELP nexus_docker_image_total_size_bytes Total size (approx) of layers belonging to docker image in a repo in bytes\n")
    resultsFile.append("# TYPE nexus_docker_image_total_size_bytes gauge\n")
    def top_counter = 0
    stat_by_image.sort{ a, b -> a.key <=> b.key }.each() { repo_name, stats -> // sort by repo name
        stats.sort{ a, b -> b.value <=> a.value }.each() { image, size ->
            if ( top_N_only && top_counter >= top_N_only ) return // dumb way ;)
            resultsFile.append("nexus_docker_image_total_size_bytes{repo=\"${repo_name}\",blobstore=\"${repo_blobstore[repo_name]}\",image=\"${image}\"} ${size} ${nowMs}\n")
            top_counter += 1
        }
    }
}
if (stat_by_image_group) {
    resultsFile.append("# HELP nexus_docker_group_total_size_bytes Total size (approx) of all docker images in a repo by group in bytes\n")
    resultsFile.append("# TYPE nexus_docker_group_total_size_bytes gauge\n")
    top_counter = 0
    stat_by_image_group.sort{ a, b -> a.key <=> b.key }.each() { repo_name, stats -> // sort by repo name
        stats.sort{ a, b -> b.value <=> a.value }.each() { image_group, size ->
            if ( top_N_only && top_counter >= top_N_only ) return // dumb way ;)
            resultsFile.append("nexus_docker_group_total_size_bytes{repo=\"${repo_name}\",blobstore=\"${repo_blobstore[repo_name]}\",image_group=\"${image_group}\"} ${size} ${nowMs}\n")
            top_counter += 1
        }
    }
}

// other assets stats
if (assets) {
    resultsFile.append("# HELP nexus_asset_total_size_bytes Total size of all assets in a repo by group in bytes\n")
    resultsFile.append("# TYPE nexus_asset_total_size_bytes gauge\n")
    assets.each{ repo_name, repo_assets ->
        repo_assets.each{ group_name, asset_info ->
            resultsFile.append("nexus_asset_total_size_bytes{repo=\"${repo_name}\",blobstore=\"${repo_blobstore[repo_name]}\",group=\"${group_name}\"} ${asset_info.size} ${nowMs}\n")
        }
    }
}

// blob store statistics
import org.sonatype.nexus.blobstore.api.BlobStoreManager

def bsm = container.lookup(org.sonatype.nexus.blobstore.api.BlobStoreManager.class.name)

resultsFile.append("# HELP nexus_blobstore_blob_count Number of blobs in a Nexus blobstore\n")
resultsFile.append("# TYPE nexus_blobstore_blob_count gauge\n")
resultsFile.append("# HELP nexus_blobstore_total_size_bytes Total size of blobs stored in a Nexus blobstore in bytes\n")
resultsFile.append("# TYPE nexus_blobstore_total_size_bytes gauge\n")
resultsFile.append("# HELP nexus_blobstore_available_bytes Available free space in a Nexus blobstore in bytes\n")
resultsFile.append("# TYPE nexus_blobstore_available_bytes gauge\n")
bsm.browse().each { bs ->
    def cfg = bs.getBlobStoreConfiguration()
    def m = bs.getMetrics()
    def state = bs.isStarted() ? 'started' : 'stopped'
    def store = cfg.getName()
    def type  = cfg.getType()

    resultsFile.append("nexus_blobstore_blob_count{store=\"${store}\",type=\"${type}\",state=\"${state}\"} ${m.getBlobCount()} ${nowMs}\n")
    resultsFile.append("nexus_blobstore_total_size_bytes{store=\"${store}\",type=\"${type}\",state=\"${state}\"} ${m.getTotalSize()} ${nowMs}\n")

    def avail = m.getAvailableSpace()
    if (avail != null) {
        resultsFile.append("nexus_blobstore_available_bytes{store=\"${store}\",type=\"${type}\",state=\"${state}\"} ${avail} ${nowMs}\n")
    }
}

log.info("report saved to ${resultsFile.toPath()}")
