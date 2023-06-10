def subfolders = [
    'DATABASE_DIR_1',
    'DATABASE_DIR_2',
    'DATABASE_DIR_3',
    'DATABASE_DIR_4',
    'DATABASE_DIR_5'
]

def subfoldersAsString = subfolders.join(',')

properties([
    parameters([
        choice(choices: ['engine_version', 'secondary_cluster_engine_version', 'primary_cluster_engine_version'], description: 'Please update the secondary first', name: 'ENGINE_VERSION'),
        choice(choices: ['12.15'], description: 'Please update the secondary first', name: 'VERSION'),
        extendedChoice(
            description: '',
            multiSelectDelimiter: ',',
            name: 'RDS_NAME',
            quoteValue: false,
            saveJSONParameterToFile: false,
            type: 'PT_CHECKBOX',
            value: subfoldersAsString,
            visibleItemCount: 5)
    ])
])

timestamps {
    node {
        try {
            git(
                branch: 'master', 
                credentialsId: '<CREDENTIALS_ID>', 
                poll: false, 
                url: 'https://github.com/<ORGANIZATION>/aws-rds-maintenance.git'
            )

            stage("Clone and Create PR") {
                cloneAndCreatePR()
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

def cloneAndCreatePR() {
    withCredentials([usernamePassword(credentialsId: '<CREDENTIALS_ID>', usernameVariable: 'GITHUB_USERNAME', passwordVariable: 'GITHUB_PASSWORD')]) {
        def ENGINE_VERSION = params.ENGINE_VERSION
        def VERSION = params.VERSION
        def RDS_NAME = params.RDS_NAME.tokenize(',')
        
        sh "git config --global user.email 'bender@sysco.com' && git config --global user.name 'bender'"
        def branchName = "feature-${UUID.randomUUID().toString().substring(0, 7)}"
        sh "git checkout -b ${branchName}"

        RDS_NAME.each { subfolder ->
            def filePath = "databases/aurora/vars/cx-shop/prod/${subfolder}/vars.tfvars.json"
            sh "awk '{sub(/\"${ENGINE_VERSION}\" *: *\"12.*\"/, \"\\\"${ENGINE_VERSION}\\\" : \\\"${VERSION}\\\"\")}1' ${filePath} > tmpfile && mv tmpfile ${filePath}"
        }

        sh '''
            git branch
            ${fileUpdates}
            git status
            git add -u
            git commit -m "RDS upgrade into 12.15"
        '''

        sh "git push --set-upstream https://${GITHUB_USERNAME}:${GITHUB_PASSWORD}@github.com/<ORGANIZATION>/aws-rds-maintenance.git ${branchName}"
 
        def base64 = "${GITHUB_USERNAME}:${GITHUB_PASSWORD}".bytes.encodeBase64().toString()
        sh """
            curl -X POST \
            -H 'Authorization: Basic $base64' \
            -H "X-GitHub-Api-Version: 2022-11-28" \
            -H "Accept: application/vnd.github+json" \
            -d '{"title":"RDS upgrade into ${VERSION} - ${ENGINE_VERSION}","body":"RDS ${ENGINE_VERSION} upgrade into ${VERSION} ${RDS_NAME}","head":"${branchName}","base":"master"}' \
            https://api.github.com/repos/<ORGANIZATION>/aws-rds-maintenance/pulls
        """
    }
}
