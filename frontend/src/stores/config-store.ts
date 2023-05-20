import {defineStore} from 'pinia'
import axios from 'axios'

interface FrontendConfig {
    domainLockedMapboxToken: string
}

interface ConfigState {
    config: FrontendConfig | undefined
}

export const useConfigStore = defineStore('config', {
    state: (): ConfigState => {
        return {
            config: undefined
        }
    },
    getters: {
        getMapboxToken: (state: ConfigState) => {
            return (): string | undefined => {
                return state.config?.domainLockedMapboxToken
            }
        }
    },
    actions: {
        async fetchConfig() {
            if (this.config) {
                return
            }
            await axios
                .get(`/api/config`)
                .then((axiosResponse) => {
                    this.config = axiosResponse.data
                })
                .catch((error) => {
                    console.log(`Error loading config: ${error}`)
                })
        }
    }
})
