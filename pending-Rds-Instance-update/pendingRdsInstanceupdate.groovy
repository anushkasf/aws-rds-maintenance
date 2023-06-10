@Library('infrastructure-pipeline-library@v2') _

properties([
    parameters([
        choice(choices: ['NON-PROD', 'PROD'], description: 'Please select the environment', name: 'ENVIRONMENT'),
        choice(choices: ['us-east-2', 'us-east-1'], description: 'Please select the region', name: 'REGION'),
        choice(choices: ['Check-Pending-Update-List', 'Update-RDS'], description: 'Choose whether to check or update the RDS', name: 'ACTION'),
        choice(choices: ['N/A','next-maintenance', 'immediate'], description: 'This is required to update-RDS."\n"What type of maintenance is going to update RDS?', name: 'MAINTENANCE_TYPE'),
    ])
])

timestamps {
    node {
        try {
            
            if (ENVIRONMENT == "NON-PROD") {
                awsAccountId = "<ACCOUNT_ID>"
            }
            if (ENVIRONMENT == "PROD") {
                awsAccountId = "<ACCOUNT_ID>"
            }

            checkout scm
            def environment = params.ENVIRONMENT
            def region      = params.REGION
            def type        = params.MAINTENANCE_TYPE

            stage('Initiate Validation') {
                echo "=================Parameter Validation================="
                echo "Environment - ${environment} ${awsAccountId}"
                echo "Action      - ${params.ACTION} ${type}"
                input message: "Do you wish to continue?"
            }

            stage("${action}-${type}") {
                if (params.ACTION == 'Update-RDS'){
                    withAWS(role: "arn:aws:iam::${awsAccountId}:role/Cross-Account-DevOps", roleAccount: "${awsAccountId}") {
                        withAWSTFUtil('0.14.2') {
                            script {
                                def clusters = getClusterName().toList()
                                println "🛢️ Checking for pending updates in cluster: ${clusters}"
                            
                                for (int i = 0; i < clusters.size(); i += 10) {
                                    def endIndex = Math.min(i + 10, clusters.size())
                                    def clusterSubset = new ArrayList<>(clusters.subList(i, endIndex))
                                
                                parallel clusterSubset.collectEntries { cluster ->
                                    ["Cluster: ${cluster}": {
                                        checkAndUpdateRDSPendingStatus(cluster, type)
                                    }]
                                }
                                
                                if (endIndex < clusters.size()) {
                                    sleep(5) // Delay of 5 seconds before starting the next batch of clusters
                                }
                                }
                            }
                        }
                    }
                }
            }

            stage("${action}") {
                if (params.ACTION == 'Check-Pending-Update-List'){
                    withAWS(role: "arn:aws:iam::${awsAccountId}:role/Cross-Account-DevOps", roleAccount: "${awsAccountId}") {
                        withAWSTFUtil('0.14.2') {
                            script {
                                def db_update_list_2 = sh(returnStdout: true, script: 'aws rds describe-pending-maintenance-actions --output json --region us-east-2 | jq -r \'.PendingMaintenanceActions[] | [.ResourceIdentifier, .PendingMaintenanceActionDetails[0].Action] | @tsv\' | grep -v "cluster" | wc -l').trim()
                                echo "🚀 ${db_update_list_2} DB Instances Pending update in us-east-2"
                            }
                            sh 'aws rds describe-pending-maintenance-actions --output json --region us-east-2 | jq -r \'.PendingMaintenanceActions[] | [.ResourceIdentifier] | @tsv\' | grep -v \"cluster\" || true'

                            script{
                                def db_update_list_1 = sh(returnStdout: true, script: 'aws rds describe-pending-maintenance-actions --output json --region us-east-1 | jq -r \'.PendingMaintenanceActions[] | [.ResourceIdentifier, .PendingMaintenanceActionDetails[0].Action] | @tsv\' | grep -v "cluster" | wc -l').trim()
                                echo "🚀 ${db_update_list_1} DB Instances Pending update in us-east-1"
                            }
                            sh 'aws rds describe-pending-maintenance-actions --output json --region us-east-1 | jq -r \'.PendingMaintenanceActions[] | [.ResourceIdentifier] | @tsv\' | grep -v \"cluster\" || true'

                            script{
                                echo "🛢️ Clusters Pending for update in us-east-2"
                                sh 'aws rds describe-pending-maintenance-actions --output json --region us-east-2 | jq -r \'.PendingMaintenanceActions[] | [.ResourceIdentifier, .PendingMaintenanceActionDetails[0].Action] | @tsv\''
                                echo "🛢️ Clusters Pending for update in us-east-1"
                                sh 'aws rds describe-pending-maintenance-actions --output json --region us-east-1 | jq -r \'.PendingMaintenanceActions[] | [.ResourceIdentifier, .PendingMaintenanceActionDetails[0].Action] | @tsv\''
                            }
                        }
                    }
                }
            }
        }
        catch (Throwable t) {
            throw t
        }
        finally {
            cleanWs()
        }
    }
}

def getClusterName() {
    def clusterList = sh(returnStdout: true,script: "aws rds describe-db-clusters --query 'DBClusters[].DBClusterIdentifier' --output text --region ${region}").trim()

    if (clusterList.isEmpty()) {
        error "🛑 No RDS clusters found"
    }
    
    return clusterList.split()
}

def getInstanceList(String cluster) { 
    def instanceList = sh(script: "aws rds describe-db-clusters --db-cluster-identifier ${cluster} --region ${region} --query 'DBClusters[].DBClusterMembers[].DBInstanceIdentifier' --output json | jq -r '.[]'",returnStdout: true).trim()
    return instanceList.split()
}

def waitForInstanceUpdate(String instanceId) {
    echo "⌛ Waiting for completion of maintenance action on instance ${instanceId}..."
    def waitCmd = sh(script: "aws rds wait db-instance-available --db-instance-identifier ${instanceId} --region ${region}")
    return waitCmd
    println "✅ Updated ${waitCmd}"
}

def getInstanceEngine(String instanceId) {
    if (instanceId == null) {
        return null
    }
    def instanceEngine = sh(script: "aws rds describe-db-instances --db-instance-identifier ${instanceId} --region ${region} --query 'DBInstances[].Engine' --output text", returnStdout: true).trim()
    return instanceEngine
}

def getInstanceState(String instanceId) {
    if (instanceId == null) {
        return null
    }
    def instanceState = sh(script: "aws rds describe-db-instances --db-instance-identifier ${instanceId} --region ${region} --query 'DBInstances[].DBInstanceStatus' --output text", returnStdout: true).trim()
    return instanceState
}



def checkAndUpdateRDSPendingStatus(cluster, type) {
    def instanceList = getInstanceList(cluster)
    println "🛢️ 👀 Brief of update ${cluster}\n${instanceList}"

    instanceList.eachWithIndex { instanceId, index ->
        // if you need to ingnore aurora-mysql enable below code block
        // def engine = getInstanceEngine(instanceId)
        // if ("${engine}" == "aurora-mysql") {
        //     println "⏩ Skipping update for Aurora MySQL instance: ${instanceId} engine is ${engine}"
        //     return
        // }
        def status = getInstanceState(instanceId)
        if ("${status}" == "stopped") {
            println "⏩ Skipping update for instance: ${instanceId} ${status}"
            return
        }
        
        def arn = sh(script: "aws rds describe-pending-maintenance-actions --region ${region} --resource-identifier arn:aws:rds:${region}:${awsAccountId}:db:${instanceId} --output json | jq -r '.PendingMaintenanceActions[].ResourceIdentifier'", returnStdout: true).trim()
        def actionName = sh(script: "aws rds describe-pending-maintenance-actions --region ${region} --resource-identifier arn:aws:rds:${region}:${awsAccountId}:db:${instanceId} --output json | jq -r '.PendingMaintenanceActions[].PendingMaintenanceActionDetails[].Action'", returnStdout: true).trim()

        if ("${arn}".isEmpty() || "${actionName}".isEmpty()) {
            println "🛑 No pending actions found for instance: ${instanceId}\nARN: ${arn}\nAction Name: ${actionName}"
        }else {
            def actionNames = sh(script: "aws rds describe-pending-maintenance-actions --region ${region} --resource-identifier arn:aws:rds:${region}:${awsAccountId}:db:${instanceId} --output json | jq -r '.PendingMaintenanceActions[].PendingMaintenanceActionDetails[].Action'", returnStdout: true).trim().split('\n')

            for (pendingActionName in actionNames) {
                println "🔄 Applying maintenance action ${actionName}-${type} on instance ${instanceId}..."
                def updateCmd = sh(script: "aws rds apply-pending-maintenance-action --resource-identifier ${arn} --apply-action ${actionName} --opt-in-type ${type} --output json --region ${region}",returnStdout: true)
                println "⌛ Updateing and wait ${updateCmd}"
                waitForInstanceUpdate(instanceId)

                // Wait for the update to complete on the first instance before proceeding to the second instance
                if (index < instanceList.size() - 1) {
                    def nextInstanceId = instanceList[index + 1]
                    println "⌛ Waiting for update to complete on the first instance: ${instanceId}"
                    waitForInstanceUpdate(nextInstanceId)
                }
            }
        }
    }
}

